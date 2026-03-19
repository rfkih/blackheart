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



}
