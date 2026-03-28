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
            WHERE t.account_id = :accountId
              AND t.account_strategy_id = :accountStrategyId
              AND t.asset = :asset
              AND t.interval = :interval
              AND t.status IN (:statuses)
            ORDER BY t.entry_time DESC
            """, nativeQuery = true)
    List<Trades> findAllActiveTrades(
            @Param("accountId") UUID accountId,
            @Param("accountStrategyId") UUID accountStrategyId,
            @Param("asset") String asset,
            @Param("interval") String interval,
            @Param("statuses") List<String> statuses
    );


}