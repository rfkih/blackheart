package id.co.blackheart.service.montecarlo;

import id.co.blackheart.dto.montecarlo.MonteCarloPathSummary;
import id.co.blackheart.dto.montecarlo.MonteCarloResponse;
import id.co.blackheart.dto.request.MonteCarloRequest;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.util.MonteCarloSimulationMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

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
        if (CollectionUtils.isEmpty(samples)) {
            throw new IllegalArgumentException("samples must not be null or empty");
        }

        int n = request.getNumberOfSimulations();
        BigDecimal ruinFloor = computeRuinFloor(initialCapital, request);

        List<PathResult> results = simulateAllPaths(samples, request, initialCapital, ruinFloor, effectiveSeed, n);
        List<PathResult> byEquity = results.stream()
                .sorted(Comparator.comparing(PathResult::finalEquity))
                .toList();

        MonteCarloPathSummary worstPath  = replayWithCurve("WORST",  byEquity.getFirst(),  samples, request, initialCapital, ruinFloor);
        MonteCarloPathSummary medianPath = replayWithCurve("MEDIAN", byEquity.get(n / 2),  samples, request, initialCapital, ruinFloor);
        MonteCarloPathSummary bestPath   = replayWithCurve("BEST",   byEquity.get(n - 1),  samples, request, initialCapital, ruinFloor);

        List<BigDecimal> sortedEquities  = byEquity.stream().map(PathResult::finalEquity).toList();
        List<BigDecimal> sortedReturns   = results.stream().map(PathResult::totalReturnPct).sorted().toList();
        List<BigDecimal> sortedDrawdowns = results.stream().map(PathResult::maxDrawdownPct).sorted().toList();

        BreachCounts counts = countBreaches(results, initialCapital);
        SourceStats source  = computeSourceStats(samples, backtestInitialCapital);

        BigDecimal nd = BigDecimal.valueOf(n);
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
                .sourceMeanTradePnl(source.mean())
                .sourceMedianTradePnl(source.median())
                .sourceStdDevTradePnl(source.stdDev())
                .sourceWinRate(source.winRate())
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
                .probabilityOfRuin(BigDecimal.valueOf(counts.ruin()).divide(nd, 6, RoundingMode.HALF_UP))
                .probabilityOfDrawdownBreach(BigDecimal.valueOf(counts.drawdown()).divide(nd, 6, RoundingMode.HALF_UP))
                .probabilityOfProfit(BigDecimal.valueOf(counts.profit()).divide(nd, 6, RoundingMode.HALF_UP))
                .bestPath(bestPath)
                .medianPath(medianPath)
                .worstPath(worstPath)
                .build();
    }

    private BigDecimal computeRuinFloor(BigDecimal initialCapital, MonteCarloRequest request) {
        return initialCapital
                .multiply(ONE.subtract(request.getRuinThresholdPct().divide(HUNDRED, 12, RoundingMode.HALF_UP)))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private List<PathResult> simulateAllPaths(
            List<TradeReturnSample> samples,
            MonteCarloRequest request,
            BigDecimal initialCapital,
            BigDecimal ruinFloor,
            long effectiveSeed,
            int n) {
        SplittableRandom seeder = new SplittableRandom(effectiveSeed);
        long[] pathSeeds = new long[n];
        for (int i = 0; i < n; i++) {
            pathSeeds[i] = seeder.nextLong();
        }

        List<PathResult> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SplittableRandom rng = new SplittableRandom(pathSeeds[i]);
            List<TradeReturnSample> sample = buildPathSample(samples, request, rng);
            results.add(simulatePath(i, pathSeeds[i], sample, initialCapital, ruinFloor,
                    request.getMaxAcceptableDrawdownPct(), false));
        }
        return results;
    }

    /** Path-outcome breach tallies — ruin / drawdown-threshold / profitable. */
    private record BreachCounts(long ruin, long drawdown, long profit) {}

    private static BreachCounts countBreaches(List<PathResult> results, BigDecimal initialCapital) {
        long ruin = results.stream().filter(PathResult::ruinBreached).count();
        long drawdown = results.stream().filter(PathResult::drawdownThresholdBreached).count();
        long profit = results.stream()
                .filter(r -> r.finalEquity().compareTo(initialCapital) > 0)
                .count();
        return new BreachCounts(ruin, drawdown, profit);
    }

    /** Distributional stats of the source (historical) trade PnLs in absolute units. */
    private record SourceStats(BigDecimal mean, BigDecimal median, BigDecimal stdDev, BigDecimal winRate) {}

    private SourceStats computeSourceStats(List<TradeReturnSample> samples, BigDecimal backtestInitialCapital) {
        List<BigDecimal> absReturns = samples.stream()
                .map(s -> s.normalizedReturn().multiply(backtestInitialCapital).setScale(SCALE, RoundingMode.HALF_UP))
                .sorted()
                .toList();

        BigDecimal m   = mean(absReturns);
        BigDecimal med = percentile(absReturns, 0.50);
        BigDecimal sd  = stdDev(absReturns, m);

        long wins = samples.stream().filter(TradeReturnSample::win).count();
        BigDecimal winRate = samples.isEmpty() ? ZERO
                : BigDecimal.valueOf(wins)
                        .divide(BigDecimal.valueOf(samples.size()), 6, RoundingMode.HALF_UP)
                        .multiply(HUNDRED);
        return new SourceStats(m, med, sd, winRate);
    }


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
                return new ArrayList<>(shuffled.subList(0, horizon));
            }
            return shuffled;
        }

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
        appendIfCapturing(curve, equity);

        for (TradeReturnSample trade : trades) {
            equity = applyTrade(equity, trade.normalizedReturn());

            if (equity.compareTo(ZERO) == 0) {
                ruinBreached = true;
                maxDrawdownPct = HUNDRED;
                appendIfCapturing(curve, equity);
                break;
            }

            DrawdownUpdate ddu = updateDrawdownAndPeak(
                    equity, peakEquity, maxDrawdownPct, drawdownBreached, maxDrawdownThreshold);
            peakEquity = ddu.peakEquity();
            maxDrawdownPct = ddu.maxDrawdownPct();
            drawdownBreached = ddu.drawdownBreached();

            if (!ruinBreached && equity.compareTo(ruinFloor) <= 0) {
                ruinBreached = true;
            }
            appendIfCapturing(curve, equity);
        }

        BigDecimal totalReturnPct = computeReturnPct(equity, initialCapital);
        return new PathResult(index, seed, equity, maxDrawdownPct, totalReturnPct,
                ruinBreached, drawdownBreached, curve);
    }

    private static BigDecimal applyTrade(BigDecimal equity, BigDecimal normalizedReturn) {
        return equity.multiply(ONE.add(normalizedReturn))
                .max(ZERO)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static void appendIfCapturing(List<BigDecimal> curve, BigDecimal equity) {
        if (curve != null) curve.add(equity);
    }

    private record DrawdownUpdate(BigDecimal peakEquity, BigDecimal maxDrawdownPct, boolean drawdownBreached) {}

    private static DrawdownUpdate updateDrawdownAndPeak(BigDecimal equity, BigDecimal peakEquity,
                                                        BigDecimal currentMaxDD, boolean ddBreached,
                                                        BigDecimal ddThreshold) {
        BigDecimal newPeak = equity.compareTo(peakEquity) > 0 ? equity : peakEquity;
        if (newPeak.compareTo(ZERO) <= 0) {
            return new DrawdownUpdate(newPeak, currentMaxDD, ddBreached);
        }
        BigDecimal dd = newPeak.subtract(equity)
                .divide(newPeak, SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED);
        BigDecimal newMaxDD = dd.compareTo(currentMaxDD) > 0 ? dd : currentMaxDD;
        boolean newDdBreached = ddBreached || dd.compareTo(ddThreshold) >= 0;
        return new DrawdownUpdate(newPeak, newMaxDD, newDdBreached);
    }

    private static BigDecimal computeReturnPct(BigDecimal equity, BigDecimal initialCapital) {
        if (initialCapital.compareTo(ZERO) <= 0) return ZERO;
        return equity.subtract(initialCapital)
                .divide(initialCapital, SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED);
    }


    private MonteCarloPathSummary replayWithCurve(
            String label,
            PathResult result,
            List<TradeReturnSample> samples,
            MonteCarloRequest request,
            BigDecimal initialCapital,
            BigDecimal ruinFloor
    ) {
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
                .divide(BigDecimal.valueOf((long) values.size() - 1), SCALE + 2, RoundingMode.HALF_UP);
        return variance.sqrt(new MathContext(SCALE + 2, RoundingMode.HALF_UP))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }
}