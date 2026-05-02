package id.co.blackheart.service.alert;

import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.StrategyDailyRealizedCurve;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.StrategyDailyRealizedCurveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 7.4 — nightly P&amp;L deviation alert.
 *
 * <p>For every PROMOTED account_strategy (enabled AND not simulated), z-score
 * the trailing {@code recent-days} realized return sum against a baseline
 * distribution drawn from the strategy's own prior {@code lookback-days}
 * daily returns. Alerts at |z| ≥ {@code z-threshold}.
 *
 * <p>Why self-baseline rather than walk-forward expected: walk_forward_run
 * stores per-fold aggregate stats (each fold = months of trades), which are
 * unitless when compared against a 7-day realized sum. The strategy's own
 * recent daily-return distribution is what an operator actually wants —
 * "is this week behaving differently from how this strategy normally
 * behaves?" — and is robust to rerolled walk-forwards or missing
 * promotion-evidence links.
 *
 * <p>Runs nightly at 01:30 UTC, after {@code GENERATE_DAILY_REALIZED_CURVE}
 * (midnight UTC) has populated yesterday's row. Profile-gated to
 * {@code !research}; the trading JVM owns the curve writer and the
 * alert pipeline.
 */
@Component
@Profile("!research")
@ConditionalOnProperty(
        name = "app.alerts.pnl-deviation.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class PnlDeviationAlertService {

    private final AlertService alertService;
    private final AccountStrategyRepository accountStrategyRepository;
    private final StrategyDailyRealizedCurveRepository curveRepository;

    @Value("${app.alerts.pnl-deviation.lookback-days:90}")
    private int lookbackDays;

    @Value("${app.alerts.pnl-deviation.recent-days:7}")
    private int recentDays;

    @Value("${app.alerts.pnl-deviation.z-threshold:2.0}")
    private double zThreshold;

    @Value("${app.alerts.pnl-deviation.min-baseline-days:30}")
    private int minBaselineDays;

    @Scheduled(cron = "${app.alerts.pnl-deviation.cron:0 30 1 * * *}", zone = "UTC")
    public void scan() {
        List<AccountStrategy> promoted;
        try {
            promoted = accountStrategyRepository.findAllPromoted();
        } catch (RuntimeException e) {
            log.error("[PnlDeviation] Failed to load promoted strategies", e);
            return;
        }
        log.info("[PnlDeviation] Scanning {} promoted strategies", promoted.size());
        for (AccountStrategy s : promoted) {
            try {
                checkStrategy(s);
            } catch (RuntimeException e) {
                log.error("[PnlDeviation] check failed for accountStrategyId={}",
                        s.getAccountStrategyId(), e);
            }
        }
    }

    void checkStrategy(AccountStrategy s) {
        UUID id = s.getAccountStrategyId();
        // Cron is zoned UTC; compute "yesterday" in UTC too so the date math
        // can't drift by ±1 day relative to when the cron actually fires.
        LocalDate end = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate start = end.minusDays(lookbackDays - 1);

        List<StrategyDailyRealizedCurve> rows =
                curveRepository.findByAccountStrategyIdAndCurveDateBetween(id, start, end);
        if (rows.size() < minBaselineDays + recentDays) {
            // Not enough trading history yet — promotion fresh, or curve
            // generator hasn't backfilled. Skip silently.
            return;
        }

        LocalDate recentCutoff = end.minusDays(recentDays - 1);

        // Partition by date, not by index: gaps in the curve (skipped day,
        // weekend) would otherwise spill recent rows into baseline. A null
        // pct is treated as "no data" and dropped — folding it as 0 would
        // deflate std and bias |z| toward false positives.
        List<Double> baselineList = new ArrayList<>(rows.size());
        List<Double> recentList = new ArrayList<>(recentDays);
        for (StrategyDailyRealizedCurve row : rows) {
            BigDecimal pct = row.getDailyWeightedReturnPct();
            if (pct == null) continue;
            double v = pct.doubleValue();
            if (row.getCurveDate().isBefore(recentCutoff)) {
                baselineList.add(v);
            } else {
                recentList.add(v);
            }
        }
        if (baselineList.size() < minBaselineDays || recentList.size() < recentDays) return;

        double[] baselineTrim = toArray(baselineList);
        double[] recentTrim = toArray(recentList);

        double mean = mean(baselineTrim);
        double std = stddev(baselineTrim, mean);
        // Degenerate baseline (all-flat strategy) — z-score undefined. Skip.
        if (std < 1e-9) return;

        double recentSum = sum(recentTrim);
        double expectedSum = mean * recentDays;
        double expectedStd = std * Math.sqrt(recentDays);
        double z = (recentSum - expectedSum) / expectedStd;

        if (Math.abs(z) < zThreshold) return;

        AlertSeverity severity = z < 0 ? AlertSeverity.CRITICAL : AlertSeverity.WARN;
        String direction = z < 0 ? "underperforming" : "overperforming";
        String message = String.format(
                "%s/%s %s — last %dd return %.3f%%, baseline %.3f%% ± %.3f%% (z=%.2f, threshold=%.1f)",
                s.getStrategyCode() != null ? s.getStrategyCode() : "?",
                s.getIntervalName() != null ? s.getIntervalName() : "?",
                direction,
                recentDays,
                recentSum,
                expectedSum,
                expectedStd,
                z,
                zThreshold);
        // Daily granularity in the dedupe key — re-evaluates fresh tomorrow,
        // but a same-day re-run (manual cron invocation, JVM restart at the
        // wrong moment) still suppresses.
        String dedupeKey = "pnl_dev_" + id + "_" + end;
        alertService.raise(severity, "PNL_DEVIATION", message, dedupeKey);
    }

    private static double mean(double[] xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.length;
    }

    private static double stddev(double[] xs, double mean) {
        if (xs.length < 2) return 0;
        double sq = 0;
        for (double x : xs) {
            double d = x - mean;
            sq += d * d;
        }
        return Math.sqrt(sq / (xs.length - 1));
    }

    private static double sum(double[] xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s;
    }

    private static double[] toArray(List<Double> xs) {
        double[] out = new double[xs.size()];
        for (int i = 0; i < xs.size(); i++) out[i] = xs.get(i);
        return out;
    }
}
