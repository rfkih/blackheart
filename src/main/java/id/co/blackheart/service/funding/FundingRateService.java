package id.co.blackheart.service.funding;

import id.co.blackheart.model.FundingRate;
import id.co.blackheart.repository.FundingRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns funding-rate persistence and lookup. Historical backfill and the
 * 8-hourly live scheduler call {@link #upsertAll(List)} after fetching from
 * Binance fapi. Per-bar backtest cost calculation calls
 * {@link #findRateAt(String, LocalDateTime)}.
 *
 * <p>Live HTTP fetching lives in {@code FapiClient} so this service stays
 * persistence-only and unit-testable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundingRateService {

    private final FundingRateRepository repository;

    /**
     * Per-symbol cache of the most recent funding-feature snapshot. Live ticks
     * fan out at 5m cadence but funding events update every 8h, so the snapshot
     * is constant for ~96 consecutive bars. Keyed on {@code latestFundingTime}
     * so a new event implicitly invalidates the entry.
     */
    private final Map<String, CachedSnapshot> snapshotCache = new ConcurrentHashMap<>();

    /**
     * Upsert a batch of funding events. Idempotent — duplicate
     * (symbol, fundingTime) tuples collapse to the existing row (PK
     * conflict). Caller passes already-parsed Binance rows; this layer is
     * transport-agnostic.
     *
     * <p>Batching: a single {@code findAllById} computes the existing-set
     * (was N+1 {@code existsById} per row). Saves go through {@code saveAll}
     * for a single Hibernate flush. A {@link DataIntegrityViolationException}
     * from a peer process inserting the same key concurrently is logged and
     * propagates — {@code @Transactional} rolls back this batch and the next
     * scheduler tick recovers (events are durable on Binance).
     *
     * @return number of NEW rows inserted (existing rows are left untouched)
     */
    @Transactional
    public int upsertAll(List<FundingRate> rows) {
        if (CollectionUtils.isEmpty(rows)) return 0;

        List<FundingRate.FundingRateId> ids = new ArrayList<>(rows.size());
        for (FundingRate row : rows) {
            ids.add(new FundingRate.FundingRateId(row.getSymbol(), row.getFundingTime()));
        }

        Set<FundingRate.FundingRateId> existing = new HashSet<>();
        for (FundingRate fr : repository.findAllById(ids)) {
            existing.add(new FundingRate.FundingRateId(fr.getSymbol(), fr.getFundingTime()));
        }

        List<FundingRate> toInsert = new ArrayList<>(rows.size());
        for (FundingRate row : rows) {
            if (!existing.contains(new FundingRate.FundingRateId(row.getSymbol(), row.getFundingTime()))) {
                toInsert.add(row);
            }
        }

        if (toInsert.isEmpty()) return 0;

        try {
            repository.saveAll(toInsert);
        } catch (DataIntegrityViolationException race) {
            log.warn("funding_rate_history UPSERT race for symbol={} (peer process inserted concurrently); " +
                            "rolling back, next tick will reconcile",
                    rows.get(0).getSymbol());
            throw race;
        }

        // Invalidate the cached snapshot — a new funding event likely changed it.
        snapshotCache.remove(rows.get(0).getSymbol());

        log.info("funding_rate_history UPSERT: symbol={} inserted={} total_in_batch={}",
                rows.get(0).getSymbol(), toInsert.size(), rows.size());
        return toInsert.size();
    }

    /**
     * Latest funding rate for {@code symbol} whose {@code fundingTime} is at
     * or before {@code boundary}. Returns {@link Optional#empty()} when no
     * funding history exists yet for the symbol — the per-bar caller treats
     * empty as "no funding cost".
     */
    public Optional<FundingRate> findRateAt(String symbol, LocalDateTime boundary) {
        if (symbol == null || boundary == null) return Optional.empty();
        return repository.findLatest(symbol, boundary);
    }

    /**
     * Convenience read for the per-bar backtest path — returns the raw
     * {@code BigDecimal} funding rate (Binance's published value, e.g.
     * 0.0001 for 1 bps) or {@link BigDecimal#ZERO} if no row covers the
     * boundary.
     */
    public BigDecimal getRateOrZero(String symbol, LocalDateTime boundary) {
        return findRateAt(symbol, boundary)
                .map(FundingRate::getFundingRate)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Most recent {@code fundingTime} we have for the symbol — used by the
     * incremental scheduler to choose the next {@code startTime}. Empty when
     * the table is cold for this symbol; backfill should run first.
     */
    public Optional<LocalDateTime> latestFundingTime(String symbol) {
        return repository.findFirstBySymbolOrderByFundingTimeDesc(symbol)
                .map(FundingRate::getFundingTime);
    }

    /**
     * Funding-rate triple for the FeatureStore projection at a given bar
     * boundary. {@code rate8h} is the latest funding event with
     * {@code fundingTime <= boundary}; {@code rate7dAvg} and {@code rateZ}
     * are computed over the rolling 7-day window. Any field is null when the
     * data is insufficient — non-perp symbol, cold-start, or a window too
     * sparse to produce a meaningful stddev.
     *
     * <p>Cached per-symbol on {@code latestFundingTime}: live ticks at 5m
     * cadence query this every bar but funding events update every 8h, so
     * cache hits cover ~96 consecutive bars between events.
     */
    public FundingFeatureSnapshot computeFundingFeatures(String symbol,
                                                         LocalDateTime boundary) {
        if (symbol == null || boundary == null) return FundingFeatureSnapshot.empty();
        Optional<FundingRate> latestOpt = repository.findLatest(symbol, boundary);
        if (latestOpt.isEmpty()) return FundingFeatureSnapshot.empty();

        FundingRate latest = latestOpt.get();
        LocalDateTime latestTime = latest.getFundingTime();

        CachedSnapshot cached = snapshotCache.get(symbol);
        if (cached != null && cached.latestFundingTime.equals(latestTime)) {
            return cached.snapshot;
        }

        List<FundingRate> window =
                repository.findInWindow(symbol, boundary.minusDays(7), boundary);
        FundingFeatureSnapshot snap = FundingFeatureSnapshot.compute(latest.getFundingRate(), window);
        snapshotCache.put(symbol, new CachedSnapshot(latestTime, snap));
        return snap;
    }

    /**
     * Same triple, but operating on a caller-supplied pre-loaded series. Used
     * by the bulk feature-store backfill so we hit the DB once per symbol,
     * not once per bar. {@code orderedSeries} must be ascending by
     * fundingTime; we binary-search it for the window each call.
     */
    public FundingFeatureSnapshot computeFundingFeaturesFromSeries(
            List<FundingRate> orderedSeries, LocalDateTime boundary) {
        if (CollectionUtils.isEmpty(orderedSeries) || boundary == null) {
            return FundingFeatureSnapshot.empty();
        }
        // Find the latest event with fundingTime <= boundary via linear scan
        // backward from the upper bound. The series is short (~21 events / 7d
        // for live, full history for backfill) and the caller usually walks
        // boundaries in order, so a linear scan is fine.
        int upperIdx = upperBoundIndex(orderedSeries, boundary);
        if (upperIdx < 0) return FundingFeatureSnapshot.empty();

        BigDecimal rate8h = orderedSeries.get(upperIdx).getFundingRate();

        // Window = events strictly after (boundary - 7d), up to and including
        // upperIdx. Walk backward until we cross the lower edge.
        LocalDateTime lower = boundary.minusDays(7);
        int lowerIdx = upperIdx;
        while (lowerIdx > 0 && orderedSeries.get(lowerIdx - 1).getFundingTime().isAfter(lower)) {
            lowerIdx--;
        }
        List<FundingRate> window = orderedSeries.subList(lowerIdx, upperIdx + 1);
        return FundingFeatureSnapshot.compute(rate8h, window);
    }

    private static int upperBoundIndex(List<FundingRate> ordered, LocalDateTime boundary) {
        int lo = 0, hi = ordered.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (!ordered.get(mid).getFundingTime().isAfter(boundary)) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** Test hook — drop the snapshot cache. Production callers don't need this; the
     *  cache invalidates automatically on {@link #upsertAll}. */
    void clearSnapshotCache() {
        snapshotCache.clear();
    }

    private record CachedSnapshot(LocalDateTime latestFundingTime, FundingFeatureSnapshot snapshot) {}

    /**
     * Per-bar funding feature triple. All fields nullable so callers can
     * propagate the "no data" state into FeatureStore as null columns.
     */
    public record FundingFeatureSnapshot(
            BigDecimal rate8h,
            BigDecimal rate7dAvg,
            BigDecimal rateZ
    ) {
        public static FundingFeatureSnapshot empty() {
            return new FundingFeatureSnapshot(null, null, null);
        }

        /**
         * The window includes {@code rate8h} as its last element. For the mean
         * we keep the full window (a 7-day average that excludes the most
         * recent event would be misleading). For the z-score we slice off the
         * sample being measured — otherwise the point dampens its own
         * deviation by 1/n and an extreme tail reads as |z|≈1 instead of |z|≈3.
         * Requires n≥4 so the peer-set stddev has at least 3 samples.
         */
        static FundingFeatureSnapshot compute(BigDecimal rate8h, List<FundingRate> window) {
            if (rate8h == null || CollectionUtils.isEmpty(window)) {
                return new FundingFeatureSnapshot(rate8h, null, null);
            }
            int n = window.size();
            BigDecimal sum = BigDecimal.ZERO;
            for (FundingRate fr : window) sum = sum.add(fr.getFundingRate());
            BigDecimal mean = sum.divide(BigDecimal.valueOf(n), 12, java.math.RoundingMode.HALF_UP);

            // Need ≥4 to leave ≥3 peers after self-exclusion.
            if (n < 4) return new FundingFeatureSnapshot(rate8h, mean, null);

            int peerN = n - 1;
            double peerSum = 0.0;
            for (int i = 0; i < peerN; i++) peerSum += window.get(i).getFundingRate().doubleValue();
            double peerMean = peerSum / peerN;

            double sumSq = 0.0;
            for (int i = 0; i < peerN; i++) {
                double d = window.get(i).getFundingRate().doubleValue() - peerMean;
                sumSq += d * d;
            }
            double variance = sumSq / (peerN - 1);
            double std = Math.sqrt(variance);
            if (std <= 0.0 || Double.isNaN(std)) {
                return new FundingFeatureSnapshot(rate8h, mean, null);
            }
            double z = (rate8h.doubleValue() - peerMean) / std;
            if (Double.isNaN(z) || Double.isInfinite(z)) {
                return new FundingFeatureSnapshot(rate8h, mean, null);
            }
            BigDecimal zBd = BigDecimal.valueOf(z).setScale(10, java.math.RoundingMode.HALF_UP);
            return new FundingFeatureSnapshot(rate8h, mean, zBd);
        }
    }
}
