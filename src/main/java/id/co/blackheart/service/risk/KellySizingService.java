package id.co.blackheart.service.risk;

import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.alert.AlertService;
import id.co.blackheart.service.alert.AlertSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * PSR-discounted half-Kelly position-size multiplier.
 *
 * <p>The classical Kelly fraction {@code f* = (p·b − q) / b} where
 * {@code p = win_rate}, {@code q = 1 − p}, {@code b = avg_win / abs(avg_loss)}
 * gives the theoretically optimal bet as a fraction of capital.  In practice
 * we use <b>half-Kelly</b> (multiply by 0.5) to guard against model error, then
 * discount further by the run's PSR (Probabilistic Sharpe Ratio, stored as
 * {@code BacktestRun.psr}).  PSR is P(true SR &gt; 0) so it's already in [0, 1]
 * and naturally shrinks the fraction when the backtest evidence is weak.
 *
 * <p>When multiple qualifying runs exist we take a PSR-weighted average of
 * their individual fractions — more-significant runs dominate.
 *
 * <p>The result is clamped to [{@code MIN_KELLY}, {@code strategy.kellyMaxFraction}]
 * and then floored at {@link #MIN_KELLY} so we never zero-out a position.
 *
 * <p>Returns 1.0 (no adjustment) when:
 * <ul>
 *   <li>Kelly sizing is disabled for the strategy ({@code kellySizingEnabled = false})</li>
 *   <li>No qualifying runs exist (fewer than {@link #MIN_TRADES_FOR_KELLY} trades)</li>
 *   <li>Any required stat is null or the payoff ratio is degenerate</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KellySizingService {

    /** Minimum total_trades for a backtest run to contribute to the Kelly estimate. */
    static final int MIN_TRADES_FOR_KELLY = 30;

    /** How many recent qualifying runs to pull for the weighted average. */
    private static final int MAX_RUNS = 5;

    /**
     * Floor on the output fraction. We never scale below 5% of intended size —
     * even a very poor Kelly result should still send a token position rather
     * than silently zeroing out (which would mask strategy misfires).
     */
    static final BigDecimal MIN_KELLY = new BigDecimal("0.05");

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AccountStrategyRepository accountStrategyRepository;
    private final BacktestRunRepository backtestRunRepository;
    private final AlertService alertService;

    /**
     * Status snapshot — exposes Kelly's current state to UI / monitoring.
     * {@code reason} explains why the multiplier is what it is (e.g.
     * "kelly disabled", "no qualifying runs", "computed from N runs").
     */
    public record KellyStatus(
            boolean enabled,
            BigDecimal currentMultiplier,
            BigDecimal maxFraction,
            int qualifyingRuns,
            String reason
    ) {}

    /**
     * Compute the Kelly multiplier and return both the value and the
     * surrounding context (qualifying-run count, reason). Used by the
     * /kelly-status endpoint for operator visibility.
     */
    public KellyStatus getStatus(UUID accountStrategyId) {
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId).orElse(null);
        if (strategy == null) {
            return new KellyStatus(false, ONE, null, 0, "strategy not found");
        }
        BigDecimal max = strategy.getKellyMaxFraction() != null
                ? strategy.getKellyMaxFraction()
                : new BigDecimal("0.2500");
        if (!Boolean.TRUE.equals(strategy.getKellySizingEnabled())) {
            return new KellyStatus(false, ONE, max, 0, "kelly disabled");
        }
        List<BacktestRun> runs = backtestRunRepository.findRecentCompletedByAccountStrategyId(
                accountStrategyId, MIN_TRADES_FOR_KELLY, MAX_RUNS);
        if (runs.isEmpty()) {
            return new KellyStatus(true, ONE, max, 0,
                    "needs ≥1 backtest run with ≥" + MIN_TRADES_FOR_KELLY + " trades");
        }
        BigDecimal multiplier = computeMultiplierFromRuns(strategy, runs);
        return new KellyStatus(true, multiplier, max, runs.size(),
                "computed from " + runs.size() + " run(s)");
    }

    /**
     * Returns the Kelly size multiplier for the strategy, in [MIN_KELLY, 1].
     * {@code 1.0} means "no adjustment" (Kelly disabled or insufficient data).
     * Any value below 1.0 means "scale down the intended size by this fraction".
     *
     * <p>Convenience overload that fetches the AccountStrategy from the DB.
     * Prefer {@link #computeKellyMultiplier(AccountStrategy)} when the caller
     * already has the entity to avoid a redundant DB round-trip.
     */
    public BigDecimal computeKellyMultiplier(UUID accountStrategyId) {
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId).orElse(null);
        return computeKellyMultiplier(strategy);
    }

    /**
     * Returns the Kelly size multiplier for the strategy, in [MIN_KELLY, 1].
     * Preferred form — pass the already-loaded AccountStrategy to skip the
     * extra DB fetch that the UUID overload would issue.
     */
    public BigDecimal computeKellyMultiplier(AccountStrategy strategy) {
        if (strategy == null || !Boolean.TRUE.equals(strategy.getKellySizingEnabled())) {
            return ONE;
        }

        List<BacktestRun> runs = backtestRunRepository.findRecentCompletedByAccountStrategyId(
                strategy.getAccountStrategyId(), MIN_TRADES_FOR_KELLY, MAX_RUNS);
        if (runs.isEmpty()) {
            // Operator enabled Kelly but the strategy has no qualifying backtest
            // history. Without this warning Kelly silently returns 1.0 forever
            // and the operator never realises sizing is unaffected. The alert
            // is dedup-keyed so we don't spam the inbox on every candle.
            log.warn("[Kelly] Enabled but inactive — no qualifying backtest runs (≥{} trades) " +
                            "for accountStrategyId={}. Run a backtest to activate Kelly sizing.",
                    MIN_TRADES_FOR_KELLY, strategy.getAccountStrategyId());
            alertService.raise(
                    AlertSeverity.INFO,
                    "KELLY_INACTIVE_NO_RUNS",
                    String.format("Kelly enabled on %s but no qualifying backtest runs (≥%d trades). " +
                                    "Sizing is unaffected until a qualifying run exists.",
                            strategy.getAccountStrategyId(), MIN_TRADES_FOR_KELLY),
                    "kelly_inactive_no_runs_" + strategy.getAccountStrategyId());
            return ONE;
        }

        return computeMultiplierFromRuns(strategy, runs);
    }

    /**
     * Pure computation extracted so {@link #getStatus} and
     * {@link #computeKellyMultiplier(AccountStrategy)} share the same math
     * without re-querying the DB.
     */
    private BigDecimal computeMultiplierFromRuns(AccountStrategy strategy, List<BacktestRun> runs) {
        BigDecimal weightedFractionSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;

        for (BacktestRun run : runs) {
            BigDecimal fraction = computeSingleRunKelly(run);
            if (fraction == null) continue;

            // PSR as weight — high-confidence runs dominate the average.
            // If PSR is absent, treat it as 0.5 (uncertain).
            // Clamp to (0.01, 1] to avoid zero/negative weights.
            BigDecimal psr = run.getPsr() != null ? run.getPsr() : new BigDecimal("0.5");
            psr = psr.max(new BigDecimal("0.01")).min(ONE);

            // fraction is pure half-Kelly (no PSR inside); PSR is applied only
            // here as the weight so the final result is exactly
            // Σ(halfKelly_i × psr_i) / Σ(psr_i) — not psr²-weighted.
            weightedFractionSum = weightedFractionSum.add(fraction.multiply(psr));
            weightSum = weightSum.add(psr);
        }

        if (weightSum.signum() <= 0) {
            // All runs produced unusable stats (degenerate avg_loss, etc.) — surface
            // it. Same dedup pattern as no-runs above.
            log.warn("[Kelly] Enabled but inactive — all {} qualifying runs produced null fractions " +
                            "(degenerate stats) for accountStrategyId={}", runs.size(),
                    strategy.getAccountStrategyId());
            alertService.raise(
                    AlertSeverity.INFO,
                    "KELLY_INACTIVE_DEGENERATE",
                    String.format("Kelly enabled on %s but all %d qualifying runs have degenerate stats. " +
                                    "Sizing is unaffected.",
                            strategy.getAccountStrategyId(), runs.size()),
                    "kelly_inactive_degenerate_" + strategy.getAccountStrategyId());
            return ONE;
        }

        BigDecimal rawFraction = weightedFractionSum.divide(weightSum, 6, RoundingMode.HALF_UP);

        BigDecimal maxFraction = strategy.getKellyMaxFraction() != null
                ? strategy.getKellyMaxFraction()
                : new BigDecimal("0.2500");
        // Apply min-cap first, then floor — so MIN_KELLY always wins
        // even when kellyMaxFraction is misconfigured below the floor value.
        BigDecimal clamped = rawFraction.min(maxFraction).max(MIN_KELLY);

        log.info("[Kelly] accountStrategyId={} raw={} clamped={} (max={})",
                strategy.getAccountStrategyId(),
                rawFraction.setScale(4, RoundingMode.HALF_UP),
                clamped.setScale(4, RoundingMode.HALF_UP),
                maxFraction);
        return clamped;
    }

    /**
     * Pure half-Kelly fraction for a single backtest run.
     * Returns {@code null} when stats are missing or the formula is degenerate.
     * PSR discounting is applied by the caller as a weight, not here — keeping
     * this method PSR-free avoids double-counting in the weighted average.
     *
     * <p>Formula: {@code f = 0.5 × (p·b − q) / b}
     * where {@code b = avg_win / |avg_loss|}, {@code p = win_rate / 100},
     * {@code q = 1 − p}.
     */
    BigDecimal computeSingleRunKelly(BacktestRun run) {
        if (run.getWinRate() == null || run.getAvgWin() == null || run.getAvgLoss() == null) {
            return null;
        }

        // win_rate is stored as percentage (e.g. 55.0 means 55%)
        BigDecimal p = run.getWinRate().divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal q = ONE.subtract(p);

        BigDecimal absLoss = run.getAvgLoss().abs();
        if (absLoss.signum() <= 0) {
            // avg_loss == 0 is degenerate (strategy never lost) — skip
            return null;
        }

        BigDecimal b = run.getAvgWin().divide(absLoss, 6, RoundingMode.HALF_UP);
        if (b.signum() <= 0) {
            return null;
        }

        // Kelly: f* = (p*b - q) / b
        BigDecimal numerator = p.multiply(b).subtract(q);
        if (numerator.signum() <= 0) {
            // Negative Kelly means the strategy has negative expectancy — don't bet at all.
            // Return MIN_KELLY rather than null so the aggregate still moves.
            log.debug("[Kelly] Negative expectancy for runId={} — using MIN_KELLY floor", run.getBacktestRunId());
            return MIN_KELLY;
        }

        BigDecimal fullKelly = numerator.divide(b, 6, RoundingMode.HALF_UP);
        return HALF.multiply(fullKelly).setScale(6, RoundingMode.HALF_UP);
    }
}
