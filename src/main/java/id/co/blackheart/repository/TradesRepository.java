package id.co.blackheart.repository;

import id.co.blackheart.model.Trades;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TradesRepository extends JpaRepository<Trades, Long> {

    @Query(value = """
        SELECT *
        FROM trades
        WHERE user_id = :userId
          AND asset = :asset
          AND strategy_name = :strategyName
          AND "interval" = :interval
          AND status = 'OPEN'
        ORDER BY entry_time DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<Trades> findLatestOpenTrade(
            @Param("userId") Long userId,
            @Param("asset") String asset,
            @Param("strategyName") String strategyName,
            @Param("interval") String interval
    );
}

