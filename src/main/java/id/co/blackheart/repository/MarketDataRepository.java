package id.co.blackheart.repository;

import id.co.blackheart.model.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, Long> {

    @Query(
            value = """
                    SELECT *
                    FROM market_data
                    WHERE symbol = :symbol
                      AND "interval" = :interval
                    ORDER BY start_time DESC
                    LIMIT 300
                    """,
            nativeQuery = true
    )
    List<MarketData> findLast300BySymbolAndInterval(
            @Param("symbol") String symbol,
            @Param("interval") String interval
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
