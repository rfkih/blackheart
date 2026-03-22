package id.co.blackheart.repository;

import id.co.blackheart.model.Trades;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradesRepository extends JpaRepository<Trades, UUID> {

    @Query(value = """
            SELECT *
            FROM trades t
            WHERE t.trade_id = :tradeId
            """, nativeQuery = true)
    Optional<Trades> findByTradeId(@Param("tradeId") UUID tradeId);

    @Query(value = """
            SELECT *
            FROM trades t
            WHERE t.user_id = :userId
              AND t.user_strategy_id = :userStrategyId
              AND t.asset = :asset
              AND t.interval = :interval
              AND t.status IN (:statuses)
            ORDER BY t.entry_time DESC
            """, nativeQuery = true)
    List<Trades> findAllActiveTrades(
            @Param("userId") UUID userId,
            @Param("userStrategyId") UUID userStrategyId,
            @Param("asset") String asset,
            @Param("interval") String interval,
            @Param("statuses") List<String> statuses
    );

    @Query(value = """
            SELECT COUNT(1)
            FROM trades t
            WHERE t.user_id = :userId
              AND t.user_strategy_id = :userStrategyId
              AND t.asset = :asset
              AND t.interval = :interval
              AND t.status IN (:statuses)
            """, nativeQuery = true)
    long countActiveTrades(
            @Param("userId") UUID userId,
            @Param("userStrategyId") UUID userStrategyId,
            @Param("asset") String asset,
            @Param("interval") String interval,
            @Param("statuses") List<String> statuses
    );

    @Query(value = """
            SELECT *
            FROM trades t
            WHERE t.asset = :asset
              AND t.status IN (:statuses)
            ORDER BY t.entry_time DESC
            """, nativeQuery = true)
    List<Trades> findAllByAssetAndStatuses(
            @Param("asset") String asset,
            @Param("statuses") List<String> statuses
    );
}