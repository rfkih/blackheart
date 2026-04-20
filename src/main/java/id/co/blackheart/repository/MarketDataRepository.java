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
}
