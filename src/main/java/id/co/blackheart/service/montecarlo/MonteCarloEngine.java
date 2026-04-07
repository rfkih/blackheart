package id.co.blackheart.service.montecarlo;

import id.co.blackheart.dto.montecarlo.MonteCarloPathSummary;
import id.co.blackheart.dto.montecarlo.MonteCarloResponse;
import id.co.blackheart.dto.request.MonteCarloRequest;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.util.MonteCarloSimulationMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/**
 * Pure simulation and statistics engine.
 * No I/O, no DB access. All state is passed in, all results are returned.
 * Designed to be unit-testable in isolation.
 */
@Slf4j
@Service
public class MonteCarloEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int SCALE = 8;

    /**
     * Default percentile levels stored as exact BigDecimal to avoid floating-point issues
     * in deduplication and key generation.
     */
    private static final List<BigDecimal> DEFAULT_PERCENTILE_LEVELS = List.of(
            new BigDecimal("0.05"), new BigDecimal("0.10"), new BigDecimal("0.25"),
            new BigDecimal("0.50"), new BigDecimal("0.75"), new BigDecimal("0.90"),
            new BigDecimal("0.95")
    );

    // ── Public types ──────────────────────────────────────────────────────────

    /**
     * Normalised per-trade return: realizedPnlAmount / backtestInitialCapital.
     * Stored as a fraction (e.g., 0.01 = 1% of original backtest capital).
     * Used directly in compounding: equity *= (1 + normalizedReturn).
     *
     * NOTE: This assumes fixed-fractional sizing relative to original backtest capital.
     * If the backtest used compounding (trade sizes grew with equity), returns from later
     * trades will be over-stated and earlier trades under-stated proportionally.
     */
    public record TradeReturnSample(BigDecimal normalizedReturn, boolean win) {}

    // ── Private simulation record ─────────────────────────────────────────────

    private record PathResult(
            int index,
            long seed,
            BigDecimal finalEquity,
            BigDecimal maxDrawdownPct,
            BigDecimal totalReturnPct,
            boolean ruinBreached,
            boolean drawdownThresholdBreached,
            List<BigDecimal> equityCurve   // null unless captureEquityCurve = true
    ) {}

    // ── Sample building ───────────────────────────────────────────────────────

    /**
     * Convert closed BacktestTrade records into normalised return samples.
     *
     * normalizedReturn = realizedPnlAmount / backtestInitialCapital
     *
     * This scales historical PnL proportionally when MC uses a different initial capital:
     *   equity[t] = equity[t-1] * (1 + normalizedReturn)
     * If MC initial capital equals backtest capital → identical absolute returns.
     * If MC capital is larger → returns scale proportionally (fixed-fractional assumption).
     */
    public List<TradeReturnSample> buildSamples(
            List<BacktestTrade> trades,
            BigDecimal backtestInitialCapital
    ) {
        if (backtestInitialCapital == null || backtestInitialCapital.compareTo(ZERO) <= 0) {
            throw new IllegalArgumentException("backtestInitialCapital must be positive");
        }

        return trades.stream()
                .filter(t -> t.getRealizedPnlAmount() != null
                        && "CLOSED".equalsIgnoreCase(t.getStatus()))
                .map(t -> new TradeReturnSample(
                        t.getRealizedPnlAmount().divide(backtestInitialCapital, 12, RoundingMode.HALF_UP),
                        t.getRealizedPnlAmount().compareTo(ZERO) > 0
                ))
                .toList();
    }

    // ── Main simulation entry point ────────────────────────────────────────────

    /**
     * @param backtestInitialCapital The original backtest capital — used solely for scaling
     *                               source trade statistics in the response. Keeps the "mean
     *                               trade PnL" figures relative to the actual historical account
     *                               rather than the (potentially different) MC capital.
     */
    public MonteCarloResponse run(
            List<TradeReturnSample> samples,
            MonteCarloRequest request,
            BigDecimal initialCapital,
            long effectiveSeed,
            UUID monteCarloRunId,
            UUID backtestRunId,
            BigDecimal backtestInitialCapital
    ) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("samples must not be null or empty");
        }

        int n = request.getNumberOfSimulations();

        BigDecimal ruinFloor = initialCapital
                .multiply(ONE.subtract(request.getRuinThresholdPct().divide(HUNDRED, 12, RoundingMode.HALF_UP)))
                .setScale(SCALE, RoundingMode.HALF_UP);

        // Generate deterministic per-path seeds from the effective seed using SplittableRandom.
        // SplittableRandom has better statistical independence between streams than java.util.Random
        // (which uses a 48-bit LCG prone to correlation between differently-seeded instances).
        SplittableRandom seeder = new SplittableRandom(effectiveSeed);
        long[] pathSeeds = new long[n];
        for (int i = 0; i < n; i++) {
            pathSeeds[i] = seeder.nextLong();
        }

        // ── First pass: all paths, no equity curve storage ────────────────────
        List<PathResult> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SplittableRandom rng = new SplittableRandom(pathSeeds[i]);
            List<TradeReturnSample> sample = buildPathSample(samples, request, rng);
            results.add(simulatePath(i, pathSeeds[i], sample, initialCapital, ruinFloor,
                    request.getMaxAcceptableDrawdownPct(), false));
        }

        // ── Identify representative paths ─────────────────────────────────────
        List<PathResult> byEquity = results.stream()
                .sorted(Comparator.comparing(PathResult::finalEquity))
                .toList();

        PathResult worstResult  = byEquity.getFirst();
        // For even n, n/2 selects the upper of the two middle elements (slightly above median).
        PathResult medianResult = byEquity.get(n / 2);
        PathResult bestResult   = byEquity.get(n - 1);

        // ── Second pass: replay 3 selected paths with full equity curve ────────
        MonteCarloPathSummary worstPath  = replayWithCurve("WORST",  worstResult,  samples, request, initialCapital, ruinFloor);
        MonteCarloPathSummary medianPath = replayWithCurve("MEDIAN", medianResult, samples, request, initialCapital, ruinFloor);
        MonteCarloPathSummary bestPath   = replayWithCurve("BEST",   bestResult,   samples, request, initialCapital, ruinFloor);

        // ── Distribution statistics ───────────────────────────────────────────
        List<BigDecimal> sortedEquities = byEquity.stream().map(PathResult::finalEquity).toList();

        List<BigDecimal> sortedReturns = results.stream()
                .map(PathResult::totalReturnPct)
                .sorted()
                .toList();

        List<BigDecimal> sortedDrawdowns = results.stream()
                .map(PathResult::maxDrawdownPct)
                .sorted()
                .toList();

        long ruinCount           = results.stream().filter(PathResult::ruinBreached).count();
        long drawdownBreachCount = results.stream().filter(PathResult::drawdownThresholdBreached).count();
        long profitCount         = results.stream()
                .filter(r -> r.finalEquity().compareTo(initialCapital) > 0)
                .count();

        BigDecimal nd = BigDecimal.valueOf(n);

        // ── Source trade statistics (always in original backtest capital units) ──
        // Uses backtestInitialCapital, not MC initialCapital, so stats reflect
        // the actual historical trade sizes regardless of any MC capital override.
        List<BigDecimal> absReturns = samples.stream()
                .map(s -> s.normalizedReturn().multiply(backtestInitialCapital).setScale(SCALE, RoundingMode.HALF_UP))
                .sorted()
                .toList();

        BigDecimal sourceMean   = mean(absReturns);
        BigDecimal sourceMedian = percentile(absReturns, 0.50);
        BigDecimal sourceStdDev = stdDev(absReturns, sourceMean);

        long wins = samples.stream().filter(TradeReturnSample::win).count();
        BigDecimal sourceWinRate = samples.isEmpty() ? ZERO
                : BigDecimal.valueOf(wins)
                        .divide(BigDecimal.valueOf(samples.size()), 6, RoundingMode.HALF_UP)
                        .multiply(HUNDRED);

        Map<String, BigDecimal> percentileMap = buildPercentileMap(sortedEquities, request.getConfidenceLevels());

        return MonteCarloResponse.builder()
                .monteCarloRunId(monteCarloRunId)
                .backtestRunId(backtestRunId)
                .simulationMode(request.getSimulationMode())
                .numberOfSimulations(n)
                .tradesUsed(samples.size())
                .initialCapital(initialCapital)
                .ruinThresholdPct(request.getRuinThresholdPct())
                .maxAcceptableDrawdownPct(request.getMaxAcceptableDrawdownPct())
                .effectiveSeed(effectiveSeed)
                .sourceMeanTradePnl(sourceMean)
                .sourceMedianTradePnl(sourceMedian)
                .sourceStdDevTradePnl(sourceStdDev)
                .sourceWinRate(sourceWinRate)
                .meanFinalEquity(mean(sortedEquities))
                .medianFinalEquity(percentile(sortedEquities, 0.50))
                .minFinalEquity(sortedEquities.getFirst())
                .maxFinalEquity(sortedEquities.get(n - 1))
                .finalEquityPercentiles(percentileMap)
                .meanTotalReturnPct(mean(sortedReturns))
                .medianTotalReturnPct(percentile(sortedReturns, 0.50))
                .minTotalReturnPct(sortedReturns.getFirst())
                .maxTotalReturnPct(sortedReturns.get(n - 1))
                .meanMaxDrawdownPct(mean(sortedDrawdowns))
                .medianMaxDrawdownPct(percentile(sortedDrawdowns, 0.50))
                .worstMaxDrawdownPct(sortedDrawdowns.get(n - 1))
                .probabilityOfRuin(
                        BigDecimal.valueOf(ruinCount).divide(nd, 6, RoundingMode.HALF_UP))
                .probabilityOfDrawdownBreach(
                        BigDecimal.valueOf(drawdownBreachCount).divide(nd, 6, RoundingMode.HALF_UP))
                .probabilityOfProfit(
                        BigDecimal.valueOf(profitCount).divide(nd, 6, RoundingMode.HALF_UP))
                .bestPath(bestPath)
                .medianPath(medianPath)
                .worstPath(worstPath)
                .build();
    }

    // ── Path sample construction ──────────────────────────────────────────────

    private List<TradeReturnSample> buildPathSample(
            List<TradeReturnSample> source,
            MonteCarloRequest request,
            SplittableRandom rng
    ) {
        Integer horizon = request.getHorizonTrades();

        if (request.getSimulationMode() == MonteCarloSimulationMode.TRADE_SEQUENCE_SHUFFLE) {
            List<TradeReturnSample> shuffled = new ArrayList<>(source);
            shuffleInPlace(shuffled, rng);
            if (horizon != null && horizon < shuffled.size()) {
                // Return a copy, not a subList view, to avoid accidental mutation of shuffled.
                return new ArrayList<>(shuffled.subList(0, horizon));
            }
            return shuffled;
        }

        // BOOTSTRAP_RETURNS: sample with replacement
        int n = horizon != null ? horizon : source.size();
        List<TradeReturnSample> sample = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sample.add(source.get(rng.nextInt(source.size())));
        }
        return sample;
    }

    /**
     * Fisher-Yates in-place shuffle using SplittableRandom.
     * Avoids java.util.Collections.shuffle which requires java.util.Random (48-bit LCG).
     */
    private void shuffleInPlace(List<TradeReturnSample> list, SplittableRandom rng) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            TradeReturnSample tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    // ── Core path simulation ──────────────────────────────────────────────────

    private PathResult simulatePath(
            int index,
            long seed,
            List<TradeReturnSample> trades,
            BigDecimal initialCapital,
            BigDecimal ruinFloor,
            BigDecimal maxDrawdownThreshold,
            boolean captureEquityCurve
    ) {
        BigDecimal equity     = initialCapital;
        BigDecimal peakEquity = initialCapital;
        BigDecimal maxDrawdownPct = ZERO;
        boolean ruinBreached      = false;
        boolean drawdownBreached  = false;

        List<BigDecimal> curve = captureEquityCurve ? new ArrayList<>() : null;
        if (captureEquityCurve) curve.add(equity);

        for (TradeReturnSample trade : trades) {
            // Compounding: equity scales by (1 + normalizedReturn).
            // Floored at zero — a real account cannot go negative.
            equity = equity
                    .multiply(ONE.add(trade.normalizedReturn()))
                    .max(ZERO)
                    .setScale(SCALE, RoundingMode.HALF_UP);

            // Early termination: once equity is exactly zero, all subsequent trades
            // produce 0 * (1+r) = 0 forever ("zombie path"). Stop here — the account
            // is blown out and there is nothing left to simulate.
            if (equity.compareTo(ZERO) == 0) {
                ruinBreached = true;
                maxDrawdownPct = HUNDRED;
                if (captureEquityCurve) curve.add(equity);
                break;
            }

            if (equity.compareTo(peakEquity) > 0) {
                peakEquity = equity;
            }

            if (peakEquity.compareTo(ZERO) > 0) {
                BigDecimal dd = peakEquity.subtract(equity)
                        .divide(peakEquity, SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED);

                if (dd.compareTo(maxDrawdownPct) > 0) {
                    maxDrawdownPct = dd;
                }
                if (!drawdownBreached && dd.compareTo(maxDrawdownThreshold) >= 0) {
                    drawdownBreached = true;
                }
            }

            if (!ruinBreached && equity.compareTo(ruinFloor) <= 0) {
                ruinBreached = true;
            }

            if (captureEquityCurve) curve.add(equity);
        }

        BigDecimal totalReturnPct = initialCapital.compareTo(ZERO) > 0
                ? equity.subtract(initialCapital)
                        .divide(initialCapital, SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED)
                : ZERO;

        return new PathResult(index, seed, equity, maxDrawdownPct, totalReturnPct,
                ruinBreached, drawdownBreached, curve);
    }

    // ── Replay selected path with curve capture ────────────────────────────────

    private MonteCarloPathSummary replayWithCurve(
            String label,
            PathResult result,
            List<TradeReturnSample> samples,
            MonteCarloRequest request,
            BigDecimal initialCapital,
            BigDecimal ruinFloor
    ) {
        // Reproduce the exact same path by replaying with the same per-path seed.
        SplittableRandom rng = new SplittableRandom(result.seed());
        List<TradeReturnSample> sample = buildPathSample(samples, request, rng);
        PathResult withCurve = simulatePath(result.index(), result.seed(), sample,
                initialCapital, ruinFloor, request.getMaxAcceptableDrawdownPct(), true);

        return MonteCarloPathSummary.builder()
                .pathIndex(result.index())
                .label(label)
                .finalEquity(result.finalEquity())
                .totalReturnPct(result.totalReturnPct())
                .maxDrawdownPct(result.maxDrawdownPct())
                .ruinBreached(result.ruinBreached())
                .drawdownThresholdBreached(result.drawdownThresholdBreached())
                .equityCurve(withCurve.equityCurve())
                .build();
    }

    // ── Statistics helpers ─────────────────────────────────────────────────────

    /**
     * Builds the percentile map from a sorted list of values.
     *
     * Deduplication uses BigDecimal natural ordering (exact equality) to avoid
     * floating-point collisions that Double.distinct() would introduce.
     *
     * Keys use plain percentage format: 0.05 → "P5", 0.051 → "P5.1", 0.25 → "P25".
     * This ensures two levels that differ in value always produce different keys.
     */
    private Map<String, BigDecimal> buildPercentileMap(
            List<BigDecimal> sortedValues,
            List<BigDecimal> customLevels
    ) {
        // Use TreeSet<BigDecimal> for exact deduplication and natural sort order.
        TreeSet<BigDecimal> levelSet = new TreeSet<>(DEFAULT_PERCENTILE_LEVELS);
        if (customLevels != null) {
            customLevels.stream()
                    .filter(p -> p != null
                            && p.compareTo(BigDecimal.ZERO) > 0
                            && p.compareTo(BigDecimal.ONE) < 0)
                    .forEach(levelSet::add);
        }

        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (BigDecimal level : levelSet) {
            String key = percentileKey(level);
            map.put(key, percentile(sortedValues, level.doubleValue()));
        }
        return map;
    }

    /**
     * Converts a fractional percentile level to a map key.
     * 0.05 → "P5", 0.10 → "P10", 0.251 → "P25.1"
     * stripTrailingZeros() + toPlainString() avoids both "P5.0" and "P5E+1" forms.
     */
    private String percentileKey(BigDecimal level) {
        BigDecimal pct = level.multiply(HUNDRED).stripTrailingZeros();
        return "P" + pct.toPlainString();
    }

    /**
     * Linear interpolation percentile on a pre-sorted list.
     */
    private BigDecimal percentile(List<BigDecimal> sorted, double p) {
        if (sorted.isEmpty()) return ZERO;
        int n = sorted.size();
        if (p <= 0.0) return sorted.get(0);
        if (p >= 1.0) return sorted.get(n - 1);

        double idx = p * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(lo + 1, n - 1);

        if (lo == hi) return sorted.get(lo);

        BigDecimal frac = BigDecimal.valueOf(idx - lo);
        return sorted.get(lo)
                .add(sorted.get(hi).subtract(sorted.get(lo)).multiply(frac))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) return ZERO;
        return values.stream()
                .reduce(ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Sample standard deviation (Bessel's correction: n-1 denominator).
     * Uses BigDecimal.sqrt(MathContext) to avoid double precision overflow on
     * large portfolio values where variance.doubleValue() could return Infinity.
     */
    private BigDecimal stdDev(List<BigDecimal> values, BigDecimal mean) {
        if (values.size() < 2) return ZERO;
        BigDecimal variance = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size() - 1), SCALE + 2, RoundingMode.HALF_UP);
        return variance.sqrt(new MathContext(SCALE + 2, RoundingMode.HALF_UP))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }
}