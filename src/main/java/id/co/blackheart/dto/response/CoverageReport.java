package id.co.blackheart.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Coverage report for the historical data integrity console.
 *
 * <p>Read-only diagnostic — never mutates. Returned by
 * {@code GET /api/v1/historical/coverage}. The unified UI uses it to decide
 * which repair operations to offer (auto-populated checkbox list with row
 * counts).
 *
 * <p>Three concerns in one report:
 * <ul>
 *   <li><b>marketData</b> — row count, expected vs actual, internal gap list.</li>
 *   <li><b>featureStore</b> — row count and how many market_data candles in
 *       the range are missing a corresponding feature_store row.</li>
 *   <li><b>nullColumns</b> — for each indicator column, the number of
 *       feature_store rows in the range with NULL. Only non-zero columns are
 *       included so the UI can render "what needs patching" directly.</li>
 *   <li><b>sanity</b> — counts of likely-corrupt market_data rows.</li>
 * </ul>
 */
public record CoverageReport(
        String symbol,
        String interval,
        LocalDateTime from,
        LocalDateTime to,
        MarketDataCoverage marketData,
        FeatureStoreCoverage featureStore,
        Map<String, Long> nullColumns,
        SanityChecks sanity
) {

    public record MarketDataCoverage(
            long actual,
            long expected,
            long missing,
            int gapCount,
            List<Gap> gaps,
            LocalDateTime earliest,
            LocalDateTime latest
    ) {
    }

    public record FeatureStoreCoverage(
            long actual,
            long missingRows
    ) {
    }

    public record Gap(
            LocalDateTime from,
            LocalDateTime to,
            long missingBars
    ) {
    }

    public record SanityChecks(
            long highLessThanLow,
            long zeroVolume,
            long duplicateStartTime
    ) {
    }
}
