package id.co.blackheart.service.marketdata;

import id.co.blackheart.dto.response.CoverageReport;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.util.MapperUtil;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only diagnostic for the historical data integrity console. Builds
 * the {@link CoverageReport} the operator sees before running any repair.
 *
 * <p>Never mutates. Each public report runs a small bounded set of aggregate
 * queries against {@code market_data} and {@code feature_store}. The most
 * expensive piece is the per-column NULL-count scan, which fans out one
 * {@code COUNT(*) FILTER (WHERE col IS NULL)} per indicator column in a
 * single SQL statement — Postgres reads the partition once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataIntegrityService {

    /**
     * Identity/scaffolding columns of {@code feature_store} that are never
     * indicator outputs and so are excluded from NULL-count scanning.
     * Including them would surface false positives (e.g.
     * {@code created_time IS NULL} would match nothing but pollutes the
     * report's column list).
     */
    private static final Set<String> NON_INDICATOR_COLUMNS = Set.of(
            "id", "id_market_data", "symbol", "interval",
            "start_time", "end_time", "price",
            "created_time", "updated_time"
    );

    /**
     * Indicator columns reflected from {@link FeatureStore}'s {@code @Column}
     * annotations at construction time. Adding a new indicator column to
     * {@code FeatureStore} (with a matching Flyway migration) makes it appear
     * in the coverage report automatically — no edits to this service.
     *
     * <p>The list deliberately includes booleans, integers, and strings —
     * any nullable column in the entity that isn't a row identifier is fair
     * game.
     */
    private final List<String> featureColumns = discoverFeatureColumns();

    private static List<String> discoverFeatureColumns() {
        List<String> cols = new ArrayList<>();
        for (Field field : FeatureStore.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) continue;
            Column column = field.getAnnotation(Column.class);
            if (column == null) continue;
            String name = column.name();
            if (name == null || name.isBlank()) continue;
            if (NON_INDICATOR_COLUMNS.contains(name)) continue;
            cols.add(name);
        }
        return List.copyOf(cols);
    }

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final MapperUtil mapperUtil;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Build the coverage report for a (symbol, interval) over a range.
     * If {@code from}/{@code to} are null, defaults to the full available
     * range for the pair. If the pair has no data, returns an empty report.
     */
    @Transactional(readOnly = true)
    public CoverageReport report(String symbol, String interval,
                                 LocalDateTime from, LocalDateTime to) {
        validate(symbol, interval);

        // Resolve range from existing data when not provided. If the pair
        // has zero rows, return a fully-zeroed report rather than throwing —
        // a never-fetched symbol is a legitimate state to diagnose.
        Timestamp pairMin = marketDataRepository.findMinStartTime(symbol, interval);
        Timestamp pairMax = marketDataRepository.findMaxStartTime(symbol, interval);
        if (pairMin == null || pairMax == null) {
            return emptyReport(symbol, interval, from, to);
        }
        if (from == null) from = pairMin.toLocalDateTime();
        if (to == null) to = pairMax.toLocalDateTime();
        if (to.isBefore(from)) {
            throw new IllegalArgumentException(
                    "to must be on or after from: from=" + from + " to=" + to);
        }

        long intervalMinutes = mapperUtil.getIntervalMinutes(interval);
        long intervalSeconds = intervalMinutes * 60L;

        // ── market_data side ─────────────────────────────────────────────
        long actualMd = marketDataRepository.countBySymbolIntervalAndRange(
                symbol, interval, from, to);

        long expectedMd = expectedBars(from, to, intervalMinutes);
        long missingMd = Math.max(0L, expectedMd - actualMd);

        List<CoverageReport.Gap> gaps = findGaps(symbol, interval, from, to,
                intervalSeconds, intervalMinutes);

        Timestamp earliestInRange = marketDataRepository.findMinStartTimeInRange(
                symbol, interval, from, to);
        Timestamp latestInRange = marketDataRepository.findMaxStartTimeInRange(
                symbol, interval, from, to);

        CoverageReport.MarketDataCoverage mdCoverage = new CoverageReport.MarketDataCoverage(
                actualMd,
                expectedMd,
                missingMd,
                gaps.size(),
                gaps,
                earliestInRange != null ? earliestInRange.toLocalDateTime() : null,
                latestInRange != null ? latestInRange.toLocalDateTime() : null
        );

        // ── feature_store side ───────────────────────────────────────────
        long actualFs = featureStoreRepository.countBySymbolIntervalAndRange(
                symbol, interval, from, to);
        long missingFs = marketDataRepository.countMissingFeatureStoreRows(
                symbol, interval, from, to);

        CoverageReport.FeatureStoreCoverage fsCoverage = new CoverageReport.FeatureStoreCoverage(
                actualFs, missingFs);

        // ── NULL counts per indicator column ─────────────────────────────
        Map<String, Long> nullColumns = computeNullColumnCounts(symbol, interval, from, to);

        // ── Sanity ───────────────────────────────────────────────────────
        CoverageReport.SanityChecks sanity = computeSanityChecks(symbol, interval, from, to);

        return new CoverageReport(symbol, interval, from, to,
                mdCoverage, fsCoverage, nullColumns, sanity);
    }

    private CoverageReport emptyReport(String symbol, String interval,
                                       LocalDateTime from, LocalDateTime to) {
        return new CoverageReport(
                symbol, interval, from, to,
                new CoverageReport.MarketDataCoverage(0, 0, 0, 0, List.of(), null, null),
                new CoverageReport.FeatureStoreCoverage(0, 0),
                Map.of(),
                new CoverageReport.SanityChecks(0, 0, 0)
        );
    }

    private long expectedBars(LocalDateTime from, LocalDateTime to, long intervalMinutes) {
        if (from == null || to == null || intervalMinutes <= 0) return 0;
        long span = Duration.between(from, to).toMinutes();
        if (span < 0) return 0;
        // Inclusive endpoints — e.g. for a 4h bar from 00:00 to 04:00, both
        // 00:00 and 04:00 are bars, so 4h span / 4h interval + 1 = 2 bars.
        return span / intervalMinutes + 1;
    }

    private List<CoverageReport.Gap> findGaps(String symbol, String interval,
                                              LocalDateTime from, LocalDateTime to,
                                              long intervalSeconds, long intervalMinutes) {
        List<Object[]> rows = marketDataRepository.findGapsBySymbolIntervalAndRange(
                symbol, interval, from, to, intervalSeconds);

        return rows.stream().map(row -> {
            LocalDateTime prevStart = ((Timestamp) row[0]).toLocalDateTime();
            LocalDateTime curStart = ((Timestamp) row[1]).toLocalDateTime();
            long deltaMinutes = Duration.between(prevStart, curStart).toMinutes();
            long missingBars = Math.max(0L, deltaMinutes / intervalMinutes - 1);
            return new CoverageReport.Gap(prevStart, curStart, missingBars);
        }).toList();
    }

    /**
     * Single SQL round-trip — one {@code COUNT(*) FILTER (WHERE col IS NULL)}
     * per indicator column. Postgres reads the partition once and computes
     * all aggregates in stride. Result is filtered to non-zero counts so
     * the UI surfaces only columns that actually need patching.
     */
    private Map<String, Long> computeNullColumnCounts(String symbol, String interval,
                                                      LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < featureColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            // featureColumns is reflected from FeatureStore @Column metadata
            // at startup — not user input, no SQL injection surface.
            // Identifiers are not bindable parameters in JDBC, so they have
            // to be inlined.
            sql.append("COUNT(*) FILTER (WHERE ")
                    .append(featureColumns.get(i))
                    .append(" IS NULL)");
        }
        sql.append(" FROM feature_store ")
                .append(" WHERE symbol = :symbol ")
                .append("   AND interval = :interval ")
                .append("   AND start_time BETWEEN :startTime AND :endTime");

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("symbol", symbol);
        q.setParameter("interval", interval);
        q.setParameter("startTime", from);
        q.setParameter("endTime", to);

        Object result = q.getSingleResult();
        // Postgres returns Object[] when the SELECT has multiple columns,
        // which it always does given the FeatureStore entity has many
        // indicator fields.
        Object[] row = (Object[]) result;

        Map<String, Long> nonZero = new LinkedHashMap<>();
        for (int i = 0; i < featureColumns.size(); i++) {
            long count = ((Number) row[i]).longValue();
            if (count > 0) {
                nonZero.put(featureColumns.get(i), count);
            }
        }
        return nonZero;
    }

    private CoverageReport.SanityChecks computeSanityChecks(String symbol, String interval,
                                                            LocalDateTime from, LocalDateTime to) {
        List<Object[]> sanityRows = marketDataRepository.findSanityCounts(
                symbol, interval, from, to);
        long highLessThanLow = 0;
        long zeroVolume = 0;
        if (!sanityRows.isEmpty()) {
            Object[] row = sanityRows.get(0);
            highLessThanLow = ((Number) row[0]).longValue();
            zeroVolume = ((Number) row[1]).longValue();
        }
        long duplicateStartTime = marketDataRepository.countDuplicateStartTimes(
                symbol, interval, from, to);
        return new CoverageReport.SanityChecks(highLessThanLow, zeroVolume, duplicateStartTime);
    }

    private void validate(String symbol, String interval) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (interval == null || interval.isBlank()) {
            throw new IllegalArgumentException("interval cannot be blank");
        }
        // Fail fast on unsupported intervals so the operator gets a 400
        // rather than a confusing zero-count report.
        mapperUtil.getIntervalMinutes(interval);
    }
}
