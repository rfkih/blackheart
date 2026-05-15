package id.co.blackheart.service.risk;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.statistics.SharpeStatistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scales every entry's notional / position size so realized strategy
 * volatility hits the account's book vol target and correlated bets don't
 * stack. Two stages applied in series:
 *
 * <ol>
 *   <li><b>Vol scaling</b> — measures the strategy's realized daily-return
 *       σ from the last 30 days of closed trades, annualizes by √252, and
 *       computes {@code scale = target / realised}. Clamped to
 *       [{@link #MIN_VOL_SCALE}, {@link #MAX_VOL_SCALE}] so a 0.5%
 *       realized vol on a thin sample doesn't 30× the position.</li>
 *   <li><b>Concurrency haircut</b> — when N other strategies on the
 *       account already have open same-side positions, scale the new
 *       entry by {@code 1 / (1 + N)}. Naive but effective: the single
 *       biggest concentration risk on a single-asset book is "every
 *       strategy fires LONG on the same candle".</li>
 * </ol>
 *
 * <p>All sizing decisions short-circuit when {@code Account.volTargetingEnabled}
 * is false — the legacy {@code riskAmount × balance} path applies. Same when
 * the strategy has fewer than {@link #MIN_DAYS_FOR_VOL_ESTIMATE} days of
 * realized history; we'd rather under-trust a thin sample than over-size on
 * a 5-day vol spike.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookVolTargetingService {

    /** History window for vol estimation. Standard quant choice. */
    private static final int VOL_WINDOW_DAYS = 30;

    /** Days of daily-bucketed history required before we trust the vol
     *  estimate. Below this we fall back to the strategy's own size. */
    private static final int MIN_DAYS_FOR_VOL_ESTIMATE = 10;

    /** Bounds on the vol-scale multiplier. Prevents one quiet strategy
     *  with a 0.3% realized σ over 30d from getting a 50× allocation. */
    private static final double MIN_VOL_SCALE = 0.25;
    private static final double MAX_VOL_SCALE = 4.0;

    private static final double SQRT_TRADING_DAYS = Math.sqrt(252);
    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private final AccountRepository accountRepository;
    private final AccountStrategyRepository accountStrategyRepository;
    private final TradesRepository tradesRepository;
    private final PortfolioRepository portfolioRepository;

    public record SizingScale(
            BigDecimal scaledSize,
            BigDecimal volScale,
            BigDecimal concurrencyHaircut,
            BigDecimal estimatedAnnualizedVolPct,
            String reason
    ) {}

    /**
     * Returns {@link SizingScale#scaledSize} = {@code baseSize} when
     * vol-targeting is off or the sample is too thin; otherwise applies
     * the vol scale and concurrency haircut. Pure read — no side effects.
     */
    @Transactional(readOnly = true)
    public SizingScale scale(UUID accountStrategyId, String side, BigDecimal baseSize) {
        if (baseSize == null || baseSize.signum() <= 0) {
            return new SizingScale(baseSize, BigDecimal.ONE, BigDecimal.ONE, null, "non-positive base size");
        }
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId).orElse(null);
        if (strategy == null) {
            return new SizingScale(baseSize, BigDecimal.ONE, BigDecimal.ONE, null, "strategy not found");
        }
        Account account = accountRepository.findByAccountId(strategy.getAccountId()).orElse(null);
        if (account == null || !Boolean.TRUE.equals(account.getVolTargetingEnabled())) {
            return new SizingScale(baseSize, BigDecimal.ONE, BigDecimal.ONE, null, "vol-targeting disabled");
        }

        BigDecimal strategyCapital = computeStrategyCapital(account, strategy);
        if (strategyCapital == null || strategyCapital.signum() <= 0) {
            return new SizingScale(baseSize, BigDecimal.ONE, BigDecimal.ONE, null,
                    "no strategy capital baseline (Portfolio empty?)");
        }

        // (1) Vol scale.
        double volScale = 1.0;
        BigDecimal annualizedVolPct = null;
        double[] dailyReturnsPct = computeDailyReturnsPct(accountStrategyId, strategyCapital);
        if (dailyReturnsPct.length < MIN_DAYS_FOR_VOL_ESTIMATE) {
            log.debug("[VolTargeting] thin sample (n={}) for {} — falling through with size={}",
                    dailyReturnsPct.length, accountStrategyId, baseSize);
        } else {
            double sd = SharpeStatistics.stddev(dailyReturnsPct);
            double annualized = sd * SQRT_TRADING_DAYS;
            annualizedVolPct = BigDecimal.valueOf(annualized).setScale(2, RoundingMode.HALF_UP);
            if (annualized > 0) {
                double target = account.getBookVolTargetPct().doubleValue();
                double raw = target / annualized;
                volScale = Math.clamp(raw, MIN_VOL_SCALE, MAX_VOL_SCALE);
            }
        }

        // (2) Concurrency haircut. N current same-side opens across the
        // account → divide by (1 + N).
        long concurrent = tradesRepository.countOpenByAccountIdAndSide(account.getAccountId(), side);
        double haircut = 1.0 / (1.0 + concurrent);

        BigDecimal volScaleBd = BigDecimal.valueOf(volScale).setScale(4, RoundingMode.HALF_UP);
        BigDecimal haircutBd = BigDecimal.valueOf(haircut).setScale(4, RoundingMode.HALF_UP);
        BigDecimal scaled = baseSize
                .multiply(volScaleBd)
                .multiply(haircutBd)
                .setScale(8, RoundingMode.DOWN);

        return new SizingScale(scaled, volScaleBd, haircutBd, annualizedVolPct,
                String.format("vol×%s haircut÷%d", volScaleBd, concurrent + 1));
    }

    /**
     * Strategy capital = {@code account_balance × capital_allocation_pct / 100}.
     * Reads the USDT row from {@code portfolio} for the account; returns null
     * if the account has no USDT row or allocation is zero.
     */
    BigDecimal computeStrategyCapital(Account account, AccountStrategy strategy) {
        Portfolio usdt = portfolioRepository
                .findByAccountIdAndAsset(account.getAccountId(), "USDT")
                .orElse(null);
        if (usdt == null || usdt.getBalance() == null) return null;
        BigDecimal alloc = strategy.getCapitalAllocationPct();
        if (alloc == null || alloc.signum() <= 0) return null;
        return usdt.getBalance()
                .multiply(alloc)
                .divide(new BigDecimal("100"), 8, RoundingMode.DOWN);
    }

    /**
     * Daily-bucketed return series (% of strategy capital). Walks every
     * trade closed in the last {@link #VOL_WINDOW_DAYS} days, attributes
     * its realized P&L to {@code exit_time::date}, then divides each day's
     * P&L by the strategy capital baseline. Days with zero closes are
     * <i>not</i> emitted — they'd compress σ artificially.
     */
    double[] computeDailyReturnsPct(UUID accountStrategyId, BigDecimal strategyCapital) {
        if (strategyCapital == null || strategyCapital.signum() <= 0) return new double[0];
        LocalDateTime since = LocalDateTime.now().minusDays(VOL_WINDOW_DAYS);
        List<Trades> trades = tradesRepository.findClosedByAccountStrategyIdSince(accountStrategyId, since);
        if (trades.isEmpty()) return new double[0];

        Map<LocalDate, BigDecimal> dailyPnl = new LinkedHashMap<>();
        for (Trades t : trades) {
            if (t.getExitTime() == null || t.getRealizedPnlAmount() == null) continue;
            dailyPnl.merge(
                    t.getExitTime().toLocalDate(),
                    t.getRealizedPnlAmount(),
                    BigDecimal::add);
        }
        if (dailyPnl.isEmpty()) return new double[0];

        double capital = strategyCapital.doubleValue();
        double[] returns = new double[dailyPnl.size()];
        int i = 0;
        for (BigDecimal pnl : dailyPnl.values()) {
            // Express as a percentage so the σ-vs-target comparison is
            // dimensionally consistent (target is also a percentage).
            returns[i++] = pnl.doubleValue() / capital * 100.0;
        }
        return returns;
    }
}
