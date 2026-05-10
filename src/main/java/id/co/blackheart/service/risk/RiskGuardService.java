package id.co.blackheart.service.risk;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.alert.AlertService;
import id.co.blackheart.service.alert.AlertSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Guards against the two ways solo trading books blow up: (a) one strategy
 * bleeding to oblivion despite "it'll come back", and (b) every strategy
 * firing the same direction on the same candle and inadvertently 4×-sizing
 * a correlated bet.
 *
 * <p>Both checks run in {@link #canOpen} before
 * {@link id.co.blackheart.service.live.LiveTradingDecisionExecutorService}
 * routes an entry decision to the exchange. A deny verdict is logged and
 * the entry is silently skipped — no exchange round-trip, no partial fill.
 *
 * <p>The kill-switch is sticky on purpose: once the rolling drawdown trips
 * it, the only way to re-arm is {@link #rearm}, which an admin invokes
 * after looking at why it tripped. Auto-clearing on the next winner would
 * defeat the entire point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskGuardService {

    /** Window for the drawdown calculation. Quant convention is 30 days. */
    private static final int DD_WINDOW_DAYS = 30;

    /**
     * Minimum closed-trade count before we trust the DD signal. Without
     * this floor a brand-new strategy with one losing trade hits the
     * pure-loss branch ({@code peak == 0}) and returns 100% DD, which
     * would auto-trip the kill switch on every default threshold. The
     * fix matches the same trade-count discipline elsewhere in the
     * platform — early signals are noise and shouldn't kill strategies.
     */
    private static final int MIN_TRADES_FOR_DD = 5;

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private final AccountStrategyRepository accountStrategyRepository;
    private final AccountRepository accountRepository;
    private final TradesRepository tradesRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final AlertService alertService;
    private final RegimeGuardService regimeGuardService;
    private final CorrelationGuardService correlationGuardService;

    /**
     * Self-reference used by the {@link #canOpen(UUID, String)} convenience
     * overload to dispatch to the @Transactional 3-arg form via the Spring
     * proxy (so the @Transactional advice actually fires). Defaults to
     * {@code this} so unit tests that build the bean without a Spring context
     * still work — production wires the proxy via {@link #setSelf}.
     */
    private RiskGuardService self = this;

    @Autowired
    public void setSelf(@Lazy RiskGuardService self) {
        this.self = self;
    }

    /**
     * Verdict for one entry attempt. {@link #allowed} is the gate; the
     * other fields are diagnostics for logs and the strategy detail UI.
     */
    public record GuardVerdict(
            boolean allowed,
            String reason,
            BigDecimal currentDdPct,
            long concurrentSameSideCount
    ) {
        public static GuardVerdict allow(BigDecimal ddPct, long concurrent) {
            return new GuardVerdict(true, null, ddPct, concurrent);
        }
        public static GuardVerdict deny(String reason, BigDecimal ddPct, long concurrent) {
            return new GuardVerdict(false, reason, ddPct, concurrent);
        }
    }

    /**
     * Check whether the strategy can open a new {@code side} entry.
     * Convenience overload that queries the FeatureStore from the DB — use the
     * 3-arg form when the caller already has the current bar's FeatureStore to
     * avoid a redundant round-trip and a subtle per-candle race condition.
     */
    public GuardVerdict canOpen(UUID accountStrategyId, String side) {
        return self.canOpen(accountStrategyId, side, null);
    }

    /**
     * Check whether the strategy can open a new {@code side} entry. Side
     * effects: trips and persists the kill-switch when DD exceeds threshold
     * and it wasn't already tripped, so subsequent calls short-circuit.
     *
     * @param featureStore the current bar's FeatureStore, or {@code null} to
     *                     let this method fetch it from the DB. Pass the
     *                     already-loaded instance when available so the call
     *                     does not issue a duplicate query and the regime gate
     *                     evaluates the same bar the strategy decision used.
     */
    @Transactional
    public GuardVerdict canOpen(UUID accountStrategyId, String side, FeatureStore featureStore) {
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId).orElse(null);
        if (strategy == null) {
            return GuardVerdict.deny("Account strategy not found: " + accountStrategyId,
                    BigDecimal.ZERO, 0);
        }

        // (1) Already-tripped is the cheapest path — bail before computing DD.
        if (Boolean.TRUE.equals(strategy.getIsKillSwitchTripped())) {
            return GuardVerdict.deny(
                    "Kill-switch already tripped: "
                            + (strategy.getKillSwitchReason() != null
                                    ? strategy.getKillSwitchReason()
                                    : "manual"),
                    BigDecimal.ZERO,
                    0);
        }

        // (2) Compute rolling 30-day DD and trip-and-deny if over threshold.
        BigDecimal ddPct = computeRolling30DayDdPct(accountStrategyId);
        BigDecimal threshold = strategy.getDdKillThresholdPct();
        if (threshold != null && ddPct.compareTo(threshold) >= 0) {
            String reason = String.format("30-day DD %s%% reached threshold %s%%",
                    ddPct.setScale(2, RoundingMode.HALF_UP), threshold);
            tripKillSwitch(strategy, reason);
            return GuardVerdict.deny(reason, ddPct, 0);
        }

        // (3) Concurrent-direction cap across the account.
        Account account = accountRepository.findByAccountId(strategy.getAccountId()).orElse(null);
        if (account == null) {
            // Defensive fallback for the impossible case where a strategy
            // has no account row. Allow rather than block on missing data.
            return GuardVerdict.allow(ddPct, 0);
        }
        long concurrent = tradesRepository.countOpenByAccountIdAndSide(account.getAccountId(), side);
        Integer cap = resolveConcurrentCap(account, side);
        if (cap != null && concurrent >= cap) {
            return GuardVerdict.deny(
                    String.format("Concurrent %s positions (%d) at account cap %d",
                            side.toUpperCase(), concurrent, cap),
                    ddPct, concurrent);
        }

        // (4) Regime gate. Use the caller-supplied FeatureStore when available
        //     and only fall back to a DB query when the caller didn't have one.
        FeatureStore effectiveFs = featureStore != null ? featureStore
                : featureStoreRepository.findLatestCompletedBySymbolAndInterval(
                        strategy.getSymbol(), strategy.getIntervalName(), LocalDateTime.now())
                        .orElse(null);
        GateVerdict regimeVerdict = regimeGuardService.check(strategy, effectiveFs);
        if (!regimeVerdict.allowed()) {
            return GuardVerdict.deny(regimeVerdict.reason(), ddPct, concurrent);
        }

        // (5) Correlation / concentration — guard against correlated same-side stacking.
        GateVerdict concVerdict = correlationGuardService.check(strategy, account, side);
        if (!concVerdict.allowed()) {
            return GuardVerdict.deny(concVerdict.reason(), ddPct, concurrent);
        }

        return GuardVerdict.allow(ddPct, concurrent);
    }

    /** Manually clear the trip state. Caller is expected to have looked at why it tripped. */
    @Transactional
    public AccountStrategy rearm(UUID accountStrategyId) {
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account strategy not found: " + accountStrategyId));
        strategy.setIsKillSwitchTripped(Boolean.FALSE);
        strategy.setKillSwitchTrippedAt(null);
        strategy.setKillSwitchReason(null);
        AccountStrategy saved = accountStrategyRepository.save(strategy);
        log.info("[RiskGuard] Re-armed kill switch | accountStrategyId={}", accountStrategyId);
        return saved;
    }

    /**
     * Rolling 30-day drawdown percentage from peak cumulative P&L. When no
     * peak has been reached (strategy started in pure-loss territory) we
     * fall back to {@code drawdown / abs(drawdown) * 100} = 100% — i.e.
     * "you've lost everything" — which deliberately trips the switch so a
     * misconfigured strategy doesn't keep firing into the void.
     *
     * <p>Public so tests can assert it without going through canOpen's
     * trip-and-persist side effect.
     */
    public BigDecimal computeRolling30DayDdPct(UUID accountStrategyId) {
        LocalDateTime since = LocalDateTime.now().minusDays(DD_WINDOW_DAYS);
        List<Trades> trades = tradesRepository.findClosedByAccountStrategyIdSince(
                accountStrategyId, since);
        // Below the trust floor the kill switch must NOT fire — a single
        // unlucky trade isn't a regime, it's a sample of one. Returning
        // ZERO keeps the strategy alive long enough to accumulate signal.
        if (trades.size() < MIN_TRADES_FOR_DD) return BigDecimal.ZERO;

        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        for (Trades t : trades) {
            BigDecimal pnl = t.getRealizedPnlAmount() != null
                    ? t.getRealizedPnlAmount() : BigDecimal.ZERO;
            cumulative = cumulative.add(pnl);
            if (cumulative.compareTo(peak) > 0) peak = cumulative;
        }

        BigDecimal drawdown = peak.subtract(cumulative);
        if (drawdown.signum() <= 0) return BigDecimal.ZERO;
        if (peak.signum() <= 0) {
            // No profit ever in this window — strategy has been bleeding.
            // Return 100% so the guard trips: we'd rather false-positive
            // here than let a pure-loss strategy keep firing.
            return new BigDecimal("100.00");
        }
        return drawdown.divide(peak, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static Integer resolveConcurrentCap(Account account, String side) {
        if (SIDE_LONG.equalsIgnoreCase(side)) return account.getMaxConcurrentLongs();
        if (SIDE_SHORT.equalsIgnoreCase(side)) return account.getMaxConcurrentShorts();
        return null;
    }

    private void tripKillSwitch(AccountStrategy strategy, String reason) {
        strategy.setIsKillSwitchTripped(Boolean.TRUE);
        strategy.setKillSwitchTrippedAt(LocalDateTime.now());
        strategy.setKillSwitchReason(reason);
        accountStrategyRepository.save(strategy);
        log.warn("[RiskGuard] Kill switch TRIPPED | accountStrategyId={} reason={}",
                strategy.getAccountStrategyId(), reason);

        UUID id = strategy.getAccountStrategyId();
        String code = strategy.getStrategyCode();
        alertService.raise(
                AlertSeverity.CRITICAL,
                "KILL_SWITCH_TRIPPED",
                String.format("DD kill-switch tripped on %s (%s) — %s",
                        code != null ? code : "?", id, reason),
                "kill_switch_dd_" + id);
    }
}
