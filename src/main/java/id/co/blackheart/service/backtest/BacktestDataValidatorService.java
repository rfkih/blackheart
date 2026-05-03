package id.co.blackheart.service.backtest;

import id.co.blackheart.repository.FeatureStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Pre-backtest data validation gate. Checks that the FeatureStore has sufficient
 * coverage for the requested symbol / interval / date range before persisting the
 * run and handing it to the async executor. A thin FeatureStore means the strategy
 * is evaluated on indicator gaps — the result is misleading at best and catastrophically
 * wrong at worst (e.g. regime columns NULL = all entries fire on every bar).
 *
 * <p>Throws {@link IllegalArgumentException} on failure, consistent with the existing
 * {@link BacktestService#validateRequest} convention — callers map this to HTTP 400.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestDataValidatorService {

    private static final double MIN_COVERAGE_RATIO = 0.50;
    private static final int    MIN_ROWS_ABSOLUTE   = 10;

    private static final Map<String, Long> INTERVAL_MINUTES = Map.ofEntries(
            Map.entry("1m",  1L),
            Map.entry("3m",  3L),
            Map.entry("5m",  5L),
            Map.entry("15m", 15L),
            Map.entry("30m", 30L),
            Map.entry("1h",  60L),
            Map.entry("2h",  120L),
            Map.entry("4h",  240L),
            Map.entry("6h",  360L),
            Map.entry("8h",  480L),
            Map.entry("12h", 720L),
            Map.entry("1d",  1440L),
            Map.entry("3d",  4320L),
            Map.entry("1w",  10080L)
    );

    private final FeatureStoreRepository featureStoreRepository;

    /**
     * Validates FeatureStore coverage for the given backtest parameters. Throws
     * {@link IllegalArgumentException} with a human-readable message if coverage
     * is insufficient.
     */
    public void validate(String symbol, String interval, LocalDateTime startTime, LocalDateTime endTime) {
        long actual = featureStoreRepository.countBySymbolIntervalAndRange(symbol, interval, startTime, endTime);

        if (actual < MIN_ROWS_ABSOLUTE) {
            throw new IllegalArgumentException(String.format(
                    "Insufficient feature data for %s/%s in [%s, %s]: found %d rows (minimum %d). " +
                    "Run the feature-store backfill for this symbol/interval before backtesting.",
                    symbol, interval, startTime, endTime, actual, MIN_ROWS_ABSOLUTE));
        }

        Long intervalMins = INTERVAL_MINUTES.get(interval.toLowerCase());
        if (intervalMins != null) {
            long spanMinutes = Duration.between(startTime, endTime).toMinutes();
            long expected = Math.max(1L, spanMinutes / intervalMins);
            double coverage = (double) actual / expected;
            if (coverage < MIN_COVERAGE_RATIO) {
                throw new IllegalArgumentException(String.format(
                        "Feature data coverage too low for %s/%s: found %d of ~%d expected rows (%.0f%% < required 50%%). " +
                        "Run the feature-store backfill for this symbol/interval before backtesting.",
                        symbol, interval, actual, expected, coverage * 100));
            }
        }

        log.debug("[BacktestDataValidator] Coverage OK | symbol={} interval={} rows={}", symbol, interval, actual);
    }
}
