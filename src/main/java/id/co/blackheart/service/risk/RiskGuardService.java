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
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongBiFunction;

/**
 * Guards against the two ways solo trading books blow up: (a) one strategy
 * bleeding to oblivion despite "it'll come back", and (b) every strategy
 * firing the same direction on the same candle and inadvertently 4×-sizing
 * a correlated bet.
 *
 * <p>The legacy live entry point is {@link #canOpen}, called from
 * {@link id.co.blackheart.service.live.LiveTradingDecisionExecutorService}
 * before routing an entry decision to the exchange. V62 added
 * {@link #evaluate(EvaluationContext)} — the same gate logic in a shape that
 * also serves the backtest path, so live and backtest go through one place.
 *
 * <p><b>Gate toggles (V62+).</b> Each gate is independently togglable per
 * strategy via {@code account_strategy.<gate>_gate_enabled}; backtest runs
 * can override per gate per strategy via {@code strategy_<gate>_overrides}
 * JSONB maps. Resolver order:
 * <ol>
 *   <li>If an override is present for {@code (strategy, gate)}, use it.</li>
 *   <li>Else use the persisted {@code account_strategy} toggle.</li>
 * </ol>
 * A disabled gate is skipped; its sub-evaluation is not invoked.
 *
 * <p><b>Kill-switch DD computation is live-only.</b> The rolling 30-day DD
 * is computed from realised live trades. Backtest doesn't share the live
 * trades repository, so the backtest path of the kill-switch gate honours
 * the {@code is_kill_switch_tripped} flag but does not freshly compute DD.
 * If you want kill-switch behaviour inside a backtest you set
 * {@code is_kill_switch_tripped=true} on the strategy beforehand; the gate
 * will then deny.
 *
 * <p>The kill-switch is sticky on purpose: once {@link #tripKillSwitch}
 * runs, the only way to clear is {@link #rearm}, invoked by an admin after
 * looking at why it tripped. Auto-clearing on the next winner would defeat
 * the entire point.
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

    // V62 — gate name constants. These keys appear in:
    //   - the per-backtest-run override map passed via EvaluationContext
    //   - the per-strategy toggle resolver in gateActive()
    // Keep stable across releases — changing a string here would silently
    // invalidate every backtest_run row's existing override map.
    public static final String GATE_KILL_SWITCH    = "kill_switch";
    public static final String GATE_REGIME         = "regime";
    public static final String GATE_CORRELATION    = "correlation";
    public static final String GATE_CONCURRENT_CAP = "concurrent_cap";

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
     *
     * <p>Used by the legacy live caller (which expected the diagnostic
     * fields). New callers (backtest) prefer {@link GateVerdict} from
     * {@link #evaluate}.
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
     * V62 — caller-supplied state needed to evaluate the gate stack without
     * coupling the service to live-only repositories. Live and backtest each
     * fill their own shape and call {@link #evaluate}.
     *
     * @param strategy        target account_strategy. Required.
     * @param account         owning account (preloaded). Required.
     * @param side            "LONG" / "SHORT".
     * @param featureStore    current bar's features for the regime gate. May
     *                        be null; regime gate then short-circuits to
     *                        skipped (does not deny).
     * @param gateOverrides   per-gate boolean override map. Keys are the
     *                        {@code GATE_*} constants. null = no overrides
     *                        (live path). Present entries take precedence
     *                        over the per-strategy persisted toggle.
     * @param openCountFor    function returning the current open-position
     *                        count for {@code (accountId, side)}. Live passes
     *                        {@code tradesRepository::countOpenByAccountIdAndSide};
     *                        backtest passes a lambda over its own state.
     */
    public record EvaluationContext(
            AccountStrategy strategy,
            Account account,
            String side,
            FeatureStore featureStore,
            Map<String, Boolean> gateOverrides,
            ToLongBiFunction<UUID, String> openCountFor
    ) {}

    /**
     * V62 — evaluate the gate stack. Returns the first denial encountered;
     * gates with their toggle off are skipped. Same code path serves live
     * and backtest — the caller supplies the {@link EvaluationContext}.
     *
     * <p>This method does NOT compute fresh drawdown or write any state.
     * The live entry path additionally runs DD computation + trip-and-save
     * via {@link #canOpen}; backtest skips DD entirely.
     */
    public GateVerdict evaluate(EvaluationContext ctx) {
        // 1. Kill switch — block if the strategy's trip flag is set AND
        //    the gate is active. DD computation is the caller's job (live
        //    only, see canOpen).
        if (gateActive(ctx, GATE_KILL_SWITCH)
                && Boolean.TRUE.equals(ctx.strategy().getIsKillSwitchTripped())) {
            return GateVerdict.deny(
                    "Kill-switch already tripped: "
                            + (ctx.strategy().getKillSwitchReason() != null
                                    ? ctx.strategy().getKillSwitchReason()
                                    : "manual"));
        }

        // 2. Regime gate — needs the current bar's FeatureStore. If we don't
        //    have one, the gate is skipped (rather than deny) so a missing
        //    feature row doesn't accidentally block trading.
        if (gateActive(ctx, GATE_REGIME) && ctx.featureStore() != null) {
            GateVerdict v = regimeGuardService.check(ctx.strategy(), ctx.featureStore());
            if (!v.allowed()) return v;
        }

        // 3. Correlation / concentration — uses account-level thresholds.
        if (gateActive(ctx, GATE_CORRELATION)) {
            GateVerdict v = correlationGuardService.check(ctx.strategy(), ctx.account(), ctx.side());
            if (!v.allowed()) return v;
        }

        // 4. Account-level concurrent-cap.
        GateVerdict concurrentVerdict = evaluateConcurrentCap(ctx);
        if (!concurrentVerdict.allowed()) return concurrentVerdict;

        return GateVerdict.allow();
    }

    /**
     * Account-level concurrent-cap gate. {@code openCountFor} is supplied by
     * the caller (live: tradesRepository; backtest: BacktestState). Returns
     * allow when the gate is disabled or no counter is available.
     */
    private GateVerdict evaluateConcurrentCap(EvaluationContext ctx) {
        if (!gateActive(ctx, GATE_CONCURRENT_CAP) || ctx.openCountFor() == null) {
            return GateVerdict.allow();
        }
        long concurrent = ctx.openCountFor().applyAsLong(
                ctx.account().getAccountId(), ctx.side());
        Integer cap = resolveConcurrentCap(ctx.account(), ctx.side());
        if (cap != null && concurrent >= cap) {
            return GateVerdict.deny(String.format(
                    "Concurrent %s positions (%d) at account cap %d",
                    ctx.side().toUpperCase(), concurrent, cap));
        }
        return GateVerdict.allow();
    }

    /**
     * Resolve whether a named gate is active for this evaluation. Override
     * map (backtest-supplied) wins; otherwise read the per-strategy persisted
     * toggle. Gate names are the {@code GATE_*} constants on this class.
     */
    private static boolean gateActive(EvaluationContext ctx, String gateName) {
        if (ctx.gateOverrides() != null) {
            Boolean override = ctx.gateOverrides().get(gateName);
            if (override != null) return override;
        }
        AccountStrategy s = ctx.strategy();
        return switch (gateName) {
            case GATE_KILL_SWITCH    -> Boolean.TRUE.equals(s.getKillSwitchGateEnabled());
            case GATE_REGIME         -> Boolean.TRUE.equals(s.getRegimeGateEnabled());
            case GATE_CORRELATION    -> Boolean.TRUE.equals(s.getCorrelationGateEnabled());
            case GATE_CONCURRENT_CAP -> Boolean.TRUE.equals(s.getConcurrentCapGateEnabled());
            default -> false;
        };
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
     * Live entry-point. Loads the strategy + account, runs DD trip-check
     * (writes state if it trips), then delegates to {@link #evaluate}.
     *
     * <p>{@code featureStore} may be null — when null this method fetches the
     * latest completed bar's FeatureStore for the regime gate. Pass the
     * already-loaded instance when available so the call does not issue a
     * duplicate query and the regime gate evaluates the same bar the strategy
     * decision used.
     */
    @Transactional
    public GuardVerdict canOpen(UUID accountStrategyId, String side, FeatureStore featureStore) {
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId).orElse(null);
        if (strategy == null) {
            return GuardVerdict.deny("Account strategy not found: " + accountStrategyId,
                    BigDecimal.ZERO, 0);
        }

        // V62 review-fix #2 — when every gate is disabled (the post-V62
        // backfill default) there is no work for evaluate() to do. Skip the
        // account / FS / concurrent-count lookups entirely. Hot path on a
        // freshly-migrated install where the operator has not opted any gate
        // back in. Adding new gates means adding their toggle to this check.
        if (allGatesDisabled(strategy)) {
            return GuardVerdict.allow(BigDecimal.ZERO, 0);
        }

        // Cheap-path short-circuit: kill-switch gate active AND already
        // tripped. We could fall through to evaluate() which would also deny
        // via the kill-switch gate, but doing so wastes an account lookup, a
        // featureStore lookup, and a concurrent-cap count. Pre-V62 callers
        // expected this short-circuit; preserve it.
        if (Boolean.TRUE.equals(strategy.getKillSwitchGateEnabled())
                && Boolean.TRUE.equals(strategy.getIsKillSwitchTripped())) {
            return GuardVerdict.deny(
                    "Kill-switch already tripped: "
                            + (strategy.getKillSwitchReason() != null
                                    ? strategy.getKillSwitchReason()
                                    : "manual"),
                    BigDecimal.ZERO, 0);
        }

        // V62 — kill-switch DD trip check. Runs BEFORE the account lookup
        // because the DD compute only needs the strategy and its trade
        // history; placing it after would let a missing-account scenario
        // (the defensive fallback below) bypass the kill switch entirely.
        // Only runs when the gate is active and the switch isn't already
        // tripped (the already-tripped case was short-circuited above).
        BigDecimal ddPct = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(strategy.getKillSwitchGateEnabled())) {
            ddPct = computeRolling30DayDdPct(accountStrategyId);
            BigDecimal threshold = strategy.getDdKillThresholdPct();
            if (threshold != null && ddPct.compareTo(threshold) >= 0) {
                String reason = String.format("30-day DD %s%% reached threshold %s%%",
                        ddPct.setScale(2, RoundingMode.HALF_UP), threshold);
                tripKillSwitch(strategy, reason);
                return GuardVerdict.deny(reason, ddPct, 0);
            }
        }

        Account account = accountRepository.findByAccountId(strategy.getAccountId()).orElse(null);
        if (account == null) {
            // Defensive fallback for the impossible case where a strategy
            // has no account row. Allow rather than block on missing data.
            return GuardVerdict.allow(ddPct, 0);
        }

        FeatureStore effectiveFs = featureStore != null ? featureStore
                : featureStoreRepository.findLatestCompletedBySymbolAndInterval(
                        strategy.getSymbol(), strategy.getIntervalName(), LocalDateTime.now())
                        .orElse(null);

        long concurrent = tradesRepository.countOpenByAccountIdAndSide(
                account.getAccountId(), side);

        EvaluationContext ctx = new EvaluationContext(
                strategy,
                account,
                side,
                effectiveFs,
                null, // live path has no per-run overrides
                tradesRepository::countOpenByAccountIdAndSide
        );
        GateVerdict v = evaluate(ctx);

        return v.allowed()
                ? GuardVerdict.allow(ddPct, concurrent)
                : GuardVerdict.deny(v.reason(), ddPct, concurrent);
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

    /**
     * V62 review-fix #2 — true when no gate is active on this strategy and
     * therefore evaluate() would skip every gate. Used by canOpen as a
     * fast-path to skip the FS / concurrent-cap lookups on freshly-migrated
     * installs where the operator hasn't opted any gate back in.
     */
    private static boolean allGatesDisabled(AccountStrategy s) {
        return !Boolean.TRUE.equals(s.getKillSwitchGateEnabled())
            && !Boolean.TRUE.equals(s.getRegimeGateEnabled())
            && !Boolean.TRUE.equals(s.getCorrelationGateEnabled())
            && !Boolean.TRUE.equals(s.getConcurrentCapGateEnabled());
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
