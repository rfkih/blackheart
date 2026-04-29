package id.co.blackheart.repository;

import id.co.blackheart.model.FeatureStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureStoreRepository extends JpaRepository<FeatureStore, Long> {

    /**
     * <b>Returns the highest-{@code start_time} row INCLUDING the currently
     * forming bar.</b> Use ONLY for informational / display surfaces (e.g.
     * sentiment broadcasts) where peeking at the in-progress candle is
     * acceptable.
     *
     * <p><b>DO NOT use this on entry-decision paths.</b> Strategies must
     * see only closed bars or they leak forward-looking data. For
     * decision-time reads use {@link #findLatestCompletedBySymbolAndInterval}
     * with a {@code boundary < now} filter, and gate execution on
     * {@code BinanceWebSocketClient.isProcessable} which requires the
     * Binance {@code k.x = true} closed-candle flag.
     */
    @Query(value = """
    SELECT *
    FROM feature_store
    WHERE symbol = :symbol
      AND interval = :interval
    ORDER BY start_time DESC
    LIMIT 1
    """, nativeQuery = true)
    Optional<FeatureStore> findLatestBySymbolAndInterval(
            @Param("symbol") String symbol,
            @Param("interval") String interval
    );

    @Query(value = """
    SELECT *
    FROM feature_store
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time BETWEEN :startTime AND :endTime
    ORDER BY start_time ASC
    """, nativeQuery = true)
    List<FeatureStore> findBySymbolIntervalAndRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query(value = """
    SELECT EXISTS (
        SELECT 1
        FROM feature_store
        WHERE symbol = :symbol
          AND interval = :interval
          AND start_time = :startTime
    )
    """, nativeQuery = true)
    boolean existsBySymbolAndIntervalAndStartTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime
    );


    @Query(value = """
    SELECT start_time
    FROM feature_store
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time BETWEEN :startTime AND :endTime
    """, nativeQuery = true)
    List<Timestamp> findExistingStartTimesInRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );



    @Query(
            value = """
            SELECT *
            FROM feature_store
            WHERE symbol = :symbol
              AND "interval" = :interval
              AND start_time = :startTime
            LIMIT 1
            """,
            nativeQuery = true
    )
    Optional<FeatureStore> findBySymbolAndIntervalAndStartTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime
    );

    /**
     * Returns the most recent completed FeatureStore row strictly before the given startTime.
     * Used by the live enrichment service to populate previousFeatureStore.
     */
    @Query(value = """
    SELECT *
    FROM feature_store
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time < :startTime
    ORDER BY start_time DESC
    LIMIT 1
    """, nativeQuery = true)
    Optional<FeatureStore> findPreviousBySymbolIntervalAndStartTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime
    );

    /**
     * Returns the most recent FeatureStore row whose start_time is before the given boundary.
     * Used by the live enrichment service to get the last COMPLETED bias candle's features.
     */
    @Query(value = """
    SELECT *
    FROM feature_store
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time < :boundary
    ORDER BY start_time DESC
    LIMIT 1
    """, nativeQuery = true)
    Optional<FeatureStore> findLatestCompletedBySymbolAndInterval(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("boundary") LocalDateTime boundary
    );

    /**
     * Returns records that are missing VCB indicator fields (bb_width IS NULL).
     * Used by the backfill job to patch legacy FeatureStore rows that were
     * computed before VCB indicators were added to TechnicalIndicatorService.
     */
    @Query(value = """
    SELECT *
    FROM feature_store
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time BETWEEN :startTime AND :endTime
      AND bb_width IS NULL
    ORDER BY start_time ASC
    """, nativeQuery = true)
    List<FeatureStore> findMissingVcbIndicatorsInRange(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

}
