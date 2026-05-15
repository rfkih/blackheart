package id.co.blackheart.repository;

import id.co.blackheart.model.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, Long> {


    /**
     * Bars whose start_time AND end_time both fall inside {@code [startTime, endTime]}
     * — the historical "fully contained" semantic. Backtest depends on this.
     *
     * <p><b>Excludes</b> the bar at {@code start_time = endTime} because its
     * end_time spills past the upper bound. New backfill code wants the
     * boundary bar included; use {@link #findBySymbolIntervalAndStartTimeRange}
     * for that case.
     */
    @Query(value = """
    SELECT *
    FROM market_data
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time >= :startTime
      AND end_time <= :endTime
    ORDER BY start_time ASC
    """, nativeQuery = true)
    List<MarketData> findBySymbolIntervalAndRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Bars whose {@code start_time} falls inside {@code [startTime, endTime]}
     * inclusive. Pairs with {@link #countBySymbolIntervalAndRange} so loaded
     * count matches the count query exactly — including the bar at the
     * upper boundary, which {@link #findBySymbolIntervalAndRange} excludes
     * (its end_time extends past {@code endTime}).
     *
     * <p>Used by the bulk backfill + slope_200 patcher paths where the
     * intent is "process every bar whose start lies in [from, to]".
     */
    @Query(value = """
    SELECT *
    FROM market_data
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time BETWEEN :startTime AND :endTime
    ORDER BY start_time ASC
    """, nativeQuery = true)
    List<MarketData> findBySymbolIntervalAndStartTimeRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Returns the most recent MarketData row whose end_time is strictly before the given boundary.
     * Used by the live enrichment service to get the last COMPLETED bias candle.
     */
    @Query(value = """
    SELECT *
    FROM market_data
    WHERE symbol = :symbol
      AND interval = :interval
      AND end_time < :boundary
    ORDER BY end_time DESC
    LIMIT 1
    """, nativeQuery = true)
    Optional<MarketData> findLatestCompletedBySymbolAndInterval(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("boundary") LocalDateTime boundary
    );

    @Query(value = """
    SELECT *
    FROM market_data
    WHERE symbol = :symbol
      AND interval = :interval
    ORDER BY start_time DESC
    LIMIT :limit
    """, nativeQuery = true)
    List<MarketData> findLatestCandles(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("limit") int limit
    );


    @Query(
            value = """
                    SELECT *
                    FROM market_data
                    WHERE symbol = :symbol
                      AND "interval" = :interval
                      AND start_time <= :startTime
                    ORDER BY start_time DESC
                    LIMIT 300
                    """,
            nativeQuery = true
    )
    List<MarketData> findLast300BySymbolAndIntervalAndTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime
    );

    @Query(
            value = """
                    SELECT *
                    FROM market_data
                    WHERE symbol = :symbol
                      AND "interval" = :interval
                    ORDER BY end_time DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    MarketData findLatestBySymbol(@Param("symbol") String symbol,@Param("interval") String interval);


    @Query(
            value = """
                SELECT EXISTS (
                    SELECT 1
                    FROM market_data
                    WHERE symbol = :symbol
                      AND interval = :interval
                      AND start_time = :startTime
                )
                """,
            nativeQuery = true
    )
    boolean existsBySymbolAndIntervalAndStartTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime
    );

    // ── Coverage diagnostics ──────────────────────────────────────────────
    // Used by MarketDataIntegrityService for the /api/v1/historical/coverage
    // read-only report. All queries are bounded by (symbol, interval, range).

    @Query(value = """
    SELECT MIN(start_time) FROM market_data
    WHERE symbol = :symbol AND interval = :interval
    """, nativeQuery = true)
    java.sql.Timestamp findMinStartTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval
    );

    @Query(value = """
    SELECT MAX(start_time) FROM market_data
    WHERE symbol = :symbol AND interval = :interval
    """, nativeQuery = true)
    java.sql.Timestamp findMaxStartTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval
    );

    @Query(value = """
    SELECT MIN(start_time) FROM market_data
    WHERE symbol = :symbol AND interval = :interval
      AND start_time BETWEEN :startTime AND :endTime
    """, nativeQuery = true)
    java.sql.Timestamp findMinStartTimeInRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query(value = """
    SELECT MAX(start_time) FROM market_data
    WHERE symbol = :symbol AND interval = :interval
      AND start_time BETWEEN :startTime AND :endTime
    """, nativeQuery = true)
    java.sql.Timestamp findMaxStartTimeInRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query(value = """
    SELECT COUNT(*) FROM market_data
    WHERE symbol = :symbol AND interval = :interval
      AND start_time BETWEEN :startTime AND :endTime
    """, nativeQuery = true)
    long countBySymbolIntervalAndRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Counts market_data rows in the range that have no corresponding
     * feature_store row. The UI offers "Backfill missing feature_store rows"
     * with this count.
     */
    @Query(value = """
    SELECT COUNT(*) FROM market_data m
    WHERE m.symbol = :symbol
      AND m.interval = :interval
      AND m.start_time BETWEEN :startTime AND :endTime
      AND NOT EXISTS (
          SELECT 1 FROM feature_store f
          WHERE f.symbol = m.symbol
            AND f.interval = m.interval
            AND f.start_time = m.start_time
      )
    """, nativeQuery = true)
    long countMissingFeatureStoreRows(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Internal gap detection via LAG. Returns rows where the time delta to
     * the previous row exceeds one interval — i.e. one or more bars missing
     * between two existing bars. Capped at 100 results to keep the report
     * payload bounded; the operator can always re-run with a tighter range.
     *
     * <p>Result tuple: {@code [Timestamp prevStart, Timestamp curStart]}.
     * The caller computes {@code missingBars} from the delta and the
     * interval (cheaper than computing it in SQL).
     */
    @Query(value = """
    WITH ordered AS (
        SELECT start_time,
               LAG(start_time) OVER (ORDER BY start_time) AS prev_start
        FROM market_data
        WHERE symbol = :symbol
          AND interval = :interval
          AND start_time BETWEEN :startTime AND :endTime
    )
    SELECT prev_start, start_time
    FROM ordered
    WHERE prev_start IS NOT NULL
      AND EXTRACT(EPOCH FROM (start_time - prev_start)) > :intervalSeconds
    ORDER BY prev_start
    LIMIT 100
    """, nativeQuery = true)
    List<Object[]> findGapsBySymbolIntervalAndRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("intervalSeconds") long intervalSeconds
    );

    /**
     * Sanity counters in one round-trip:
     * {@code [highLessThanLow, zeroVolumeAndZeroTrades]}. Both should be 0
     * for healthy Binance ingestion; non-zero indicates either a bad fetch
     * (incomplete row) or genuinely degenerate market behavior worth
     * investigating.
     */
    @Query(value = """
    SELECT
        COUNT(*) FILTER (WHERE high_price < low_price)              AS high_less_than_low,
        COUNT(*) FILTER (WHERE volume = 0 AND trade_count = 0)      AS zero_volume
    FROM market_data
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time BETWEEN :startTime AND :endTime
    """, nativeQuery = true)
    List<Object[]> findSanityCounts(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Should always return 0 — the unique constraint
     * {@code (symbol, interval, start_time)} prevents duplicates. Surveilling
     * it anyway: if it ever returns non-zero we have a corruption to triage.
     */
    @Query(value = """
    SELECT COUNT(*) FROM (
        SELECT start_time
        FROM market_data
        WHERE symbol = :symbol
          AND interval = :interval
          AND start_time BETWEEN :startTime AND :endTime
        GROUP BY start_time
        HAVING COUNT(*) > 1
    ) dups
    """, nativeQuery = true)
    long countDuplicateStartTimes(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
}
