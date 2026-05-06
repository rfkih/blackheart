package id.co.blackheart.service.technicalindicator.patcher;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.service.marketdata.job.JobContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator for the {@link FeaturePatcher} framework. Walks month-sized
 * windows over a (symbol, interval) range, lets the patcher build its aux
 * data per window, then iterates NULL rows in chunks of 500 with progress
 * emits and cooperative cancel checks between chunks.
 *
 * <p>Auto-discovery: when the operator submits PATCH_NULL_COLUMN with no
 * (symbol, interval), {@link #findPairsWithNullColumn(String)} enumerates
 * every distinct pair that has at least one NULL row. The orchestrator
 * runs each in turn under the same job — the slope_200 backfill pattern
 * generalized.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeaturePatcherService {

    /** Size of save-buffer flushes. Bounds Hibernate L1 cache memory under wide patches. */
    private static final int CHUNK_SIZE = 500;

    /** Window slice — keeps heap bounded for high-frequency intervals (5m BTC ≈ 8.6k bars/month). */
    private static final int WINDOW_MONTHS = 1;

    private final FeatureStoreRepository featureStoreRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Auto-discover all distinct (symbol, interval) pairs where at least one
     * feature_store row has NULL in {@code column}. Used by the
     * PATCH_NULL_COLUMN handler when no specific pair is requested.
     */
    @Transactional(readOnly = true)
    public List<SymbolInterval> findPairsWithNullColumn(String column) {
        // column is from a registered patcher's primaryColumn(), validated
        // against [a-z_][a-z0-9_]* by FeaturePatcherRegistry. Safe to inline.
        String sql = "SELECT DISTINCT symbol, interval FROM feature_store"
                + " WHERE " + column + " IS NULL"
                + " ORDER BY symbol, interval";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();

        List<SymbolInterval> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new SymbolInterval((String) row[0], (String) row[1]));
        }
        return result;
    }

    /**
     * Run the patcher across all auto-discovered pairs that need it.
     * Returns a map of "symbol/interval" → rows updated.
     */
    public Map<String, Integer> patchAllPairs(FeaturePatcher<?> patcher, JobContext ctx) {
        List<SymbolInterval> pairs = findPairsWithNullColumn(patcher.primaryColumn());
        if (pairs.isEmpty()) {
            log.info("Patcher {}: no pairs need patching", patcher.primaryColumn());
            return Map.of();
        }

        log.info("Patcher {}: auto-discovered {} pair(s) needing work",
                patcher.primaryColumn(), pairs.size());

        Map<String, Integer> result = new LinkedHashMap<>();
        int pairsDone = 0;
        for (SymbolInterval pair : pairs) {
            if (ctx != null && ctx.isCancellationRequested()) {
                log.info("Patcher {}: cancellation requested, stopping at {}/{} pairs",
                        patcher.primaryColumn(), pairsDone, pairs.size());
                break;
            }
            ctx.setPhase("patch:" + patcher.primaryColumn() + " " + pair.symbol() + "/" + pair.interval());
            int updated = patchPair(patcher, pair.symbol(), pair.interval(), null, null, ctx);
            result.put(pair.symbol() + "/" + pair.interval(), updated);
            pairsDone++;
            ctx.setProgress(pairsDone, pairs.size());
        }
        return result;
    }

    /**
     * Run the patcher across one pair. {@code from}/{@code to} default to
     * the pair's earliest/latest NULL row when null.
     */
    public int patchPair(FeaturePatcher<?> patcher, String symbol, String interval,
                         LocalDateTime from, LocalDateTime to, JobContext ctx) {
        if (from == null || to == null) {
            LocalDateTime[] bounds = findNullBounds(patcher.primaryColumn(), symbol, interval);
            if (bounds == null) return 0;
            if (from == null) from = bounds[0];
            if (to == null) to = bounds[1];
        }

        int total = 0;
        LocalDateTime windowStart = from;
        while (!windowStart.isAfter(to)) {
            if (ctx != null && ctx.isCancellationRequested()) break;
            LocalDateTime windowEnd = windowStart.plusMonths(WINDOW_MONTHS);
            if (windowEnd.isAfter(to)) windowEnd = to;
            total += patchWindow(patcher, symbol, interval, windowStart, windowEnd);
            windowStart = windowEnd.plusNanos(1);
        }

        log.info("Patcher {} for {}/{}: total updated={}",
                patcher.primaryColumn(), symbol, interval, total);
        return total;
    }

    @Transactional
    public <T> int patchWindow(FeaturePatcher<T> patcher, String symbol, String interval,
                               LocalDateTime windowStart, LocalDateTime windowEnd) {
        List<FeatureStore> nullRows = findNullRowsInWindow(
                patcher.primaryColumn(), symbol, interval, windowStart, windowEnd);
        if (nullRows.isEmpty()) return 0;

        T aux = patcher.buildAux(symbol, interval, windowStart, windowEnd);
        if (aux == null) {
            log.debug("Patcher {} returned null aux for {}/{} window [{} → {}] — skipping",
                    patcher.primaryColumn(), symbol, interval, windowStart, windowEnd);
            return 0;
        }

        // NOTE on memory: nullRows is bounded by the orchestrator's 1-month
        // window (~720 rows at 1h, ~8.6k at 5m). We DO NOT call
        // entityManager.clear() between chunks here — `nullRows` holds
        // managed entities, and clearing the session mid-iteration would
        // detach the unprocessed tail. saveAll() on a detached entity falls
        // into merge() → one extra SELECT per row, defeating the chunked
        // batch. The per-window cap keeps L1 cache growth acceptable.
        int updated = 0;
        List<FeatureStore> buffer = new ArrayList<>(CHUNK_SIZE);
        for (FeatureStore row : nullRows) {
            patcher.patchRow(row, aux);
            buffer.add(row);
            updated++;
            if (buffer.size() >= CHUNK_SIZE) {
                featureStoreRepository.saveAll(buffer);
                featureStoreRepository.flush();
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            featureStoreRepository.saveAll(buffer);
            featureStoreRepository.flush();
        }
        return updated;
    }

    /**
     * Find earliest + latest start_time of rows where {@code column} IS NULL
     * for the given pair. Returns null if no NULL rows exist.
     */
    @Transactional(readOnly = true)
    public LocalDateTime[] findNullBounds(String column, String symbol, String interval) {
        String sql = "SELECT MIN(start_time), MAX(start_time) FROM feature_store"
                + " WHERE symbol = :symbol AND interval = :interval"
                + "   AND " + column + " IS NULL";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("symbol", symbol);
        q.setParameter("interval", interval);
        Object[] row = (Object[]) q.getSingleResult();
        Timestamp min = (Timestamp) row[0];
        Timestamp max = (Timestamp) row[1];
        if (min == null || max == null) return null;
        return new LocalDateTime[] { min.toLocalDateTime(), max.toLocalDateTime() };
    }

    /**
     * Loads NULL rows in the window, sorted by start_time. Pulls full
     * managed entities (not projections) because the patcher mutates them
     * and the orchestrator persists the dirty checkpoints.
     */
    @SuppressWarnings("unchecked")
    private List<FeatureStore> findNullRowsInWindow(String column, String symbol, String interval,
                                                    LocalDateTime windowStart, LocalDateTime windowEnd) {
        String sql = "SELECT * FROM feature_store"
                + " WHERE symbol = :symbol"
                + "   AND interval = :interval"
                + "   AND start_time BETWEEN :startTime AND :endTime"
                + "   AND " + column + " IS NULL"
                + " ORDER BY start_time ASC";
        Query q = entityManager.createNativeQuery(sql, FeatureStore.class);
        q.setParameter("symbol", symbol);
        q.setParameter("interval", interval);
        q.setParameter("startTime", windowStart);
        q.setParameter("endTime", windowEnd);
        List<FeatureStore> rows = (List<FeatureStore>) q.getResultList();
        rows.sort(Comparator.comparing(FeatureStore::getStartTime));
        return rows;
    }

    public record SymbolInterval(String symbol, String interval) {
    }
}
