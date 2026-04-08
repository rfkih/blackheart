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

    @Query(value = """
        SELECT *
        FROM feature_store fs
        WHERE fs.symbol = :symbol
          AND fs.interval = :interval
        ORDER BY fs.start_time DESC
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


    @Query(value = """
    SELECT *
    FROM feature_store
    WHERE symbol = :symbol
      AND interval = :interval
      AND start_time = :startTime
    LIMIT 1
    """, nativeQuery = true)
    Optional<FeatureStore> getFeatureForBacktest(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime
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
