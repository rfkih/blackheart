package id.co.blackheart.service.risk;

import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.statistics.SharpeStatistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Per-symbol slippage model fit from the user's own realized fills.
 *
 * <p>Phase 2c stamped {@code intended_entry_price} on every Trade row at
 * decision time; once a trade has executed we have an apples-to-apples
 * pair (intended vs realized fill). The mean of those deltas <i>is</i>
 * the calibrated slippage estimate — no industry-convention guess
 * required.
 *
 * <p>Why this matters: the platform's hardcoded {@code DEFAULT_SLIPPAGE_RATE
 * = 0.0005} (5 bps) was a sensible-but-arbitrary placeholder. If actual
 * fills consistently come in at 12 bps, every backtest under-estimates
 * cost and every "winning" parameter combo is more fragile in production
 * than the leaderboard suggests. Calibration replaces the placeholder
 * with a number we can defend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlippageCalibrationService {

    /** Sample window — most-recent N trades per symbol. Enough for a stable
     *  estimate without dragging in pre-incident regime data. */
    private static final int SAMPLE_LIMIT = 500;

    /** Below this many trades the estimate isn't trustworthy; callers fall
     *  back to the platform default rather than committing to a coin-flip. */
    private static final int MIN_SAMPLE_FOR_TRUST = 20;

    private final TradesRepository tradesRepository;

    public record SlippageStats(
            String symbol,
            int sampleSize,
            /** Mean signed slippage in bps. Positive = filled worse than intended. */
            BigDecimal meanBps,
            /** Stddev of bps slippage across the sample. */
            BigDecimal stddevBps,
            /** 95th-percentile absolute slippage — useful for stress-testing. */
            BigDecimal p95AbsBps,
            /** Whether the sample is large enough to be trustworthy. */
            boolean trustworthy
    ) {}

    /**
     * Compute slippage stats for a symbol. Returns {@link Optional#empty()}
     * when the asset has no closed trades with intent — there's nothing to
     * calibrate against and we'd rather signal absence than fabricate.
     */
    public Optional<SlippageStats> calibrate(String symbol) {
        if (!StringUtils.hasText(symbol)) return Optional.empty();
        List<Trades> sample = tradesRepository.findRecentWithIntent(symbol, SAMPLE_LIMIT);
        if (sample.isEmpty()) return Optional.empty();

        double[] bpsArray = sample.stream()
                .mapToDouble(SlippageCalibrationService::signedSlippageBps)
                .filter(Double::isFinite)
                .toArray();
        if (bpsArray.length == 0) return Optional.empty();

        double meanBps = SharpeStatistics.mean(bpsArray);
        double stddevBps = bpsArray.length >= 2 ? SharpeStatistics.stddev(bpsArray) : 0.0;
        double p95Abs = percentileAbs(bpsArray, 0.95);

        return Optional.of(new SlippageStats(
                symbol,
                bpsArray.length,
                BigDecimal.valueOf(meanBps).setScale(3, RoundingMode.HALF_UP),
                BigDecimal.valueOf(stddevBps).setScale(3, RoundingMode.HALF_UP),
                BigDecimal.valueOf(p95Abs).setScale(3, RoundingMode.HALF_UP),
                bpsArray.length >= MIN_SAMPLE_FOR_TRUST
        ));
    }

    /**
     * Convenience: returns the slippage rate as a decimal fraction (e.g.
     * 0.0007 for 7 bps) ready to drop into {@code BacktestRunRequest}.
     * Empty when the sample is too thin to trust — callers fall back to
     * the legacy default.
     */
    public Optional<BigDecimal> calibratedRateAsFraction(String symbol) {
        return calibrate(symbol)
                .filter(SlippageStats::trustworthy)
                .map(s -> {
                    // Use absolute mean — slippage in a backtest cost model
                    // should always be a non-negative drag. If realized
                    // mean is mildly negative (price-improvement), don't
                    // make the backtest unrealistically optimistic.
                    BigDecimal absMean = s.meanBps().abs();
                    return absMean.divide(new BigDecimal("10000"), 8, RoundingMode.HALF_UP);
                });
    }

    /**
     * Signed slippage in basis points. Positive = trade was filled at a
     * worse price than the strategy intended. The sign flips based on
     * side because "worse" means higher entry for LONG, lower for SHORT.
     */
    private static double signedSlippageBps(Trades t) {
        BigDecimal intended = t.getIntendedEntryPrice();
        BigDecimal actual = t.getAvgEntryPrice();
        if (intended == null || actual == null) return Double.NaN;
        if (intended.signum() <= 0) return Double.NaN;
        boolean isShort = "SHORT".equalsIgnoreCase(t.getSide());
        // For LONG: bps = (actual - intended) / intended × 10000
        // For SHORT: bps = (intended - actual) / intended × 10000
        BigDecimal numerator = isShort
                ? intended.subtract(actual)
                : actual.subtract(intended);
        return numerator
                .divide(intended, 10, RoundingMode.HALF_UP)
                .doubleValue() * 10_000.0;
    }

    /** 95th-percentile of absolute values. Sorted-array nearest-rank. */
    private static double percentileAbs(double[] xs, double p) {
        double[] absSorted = Arrays.stream(xs).map(Math::abs).sorted().toArray();
        if (absSorted.length == 0) return 0.0;
        int idx = Math.min(absSorted.length - 1,
                (int) Math.ceil(p * absSorted.length) - 1);
        return absSorted[Math.max(0, idx)];
    }
}
