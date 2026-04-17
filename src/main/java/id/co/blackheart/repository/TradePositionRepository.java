package id.co.blackheart.repository;

import id.co.blackheart.model.TradePosition;
import id.co.blackheart.projection.TradePositionDailyAggregateProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradePositionRepository extends JpaRepository<TradePosition, UUID> {

    @Query(value = """
            SELECT *
            FROM trade_positions tp
            WHERE tp.trade_position_id = :tradePositionId
            """, nativeQuery = true)
    Optional<TradePosition> findByTradePositionId(@Param("tradePositionId") UUID tradePositionId);

    @Query(value = """
            SELECT *
            FROM trade_positions tp
            WHERE tp.trade_id = :tradeId
            ORDER BY tp.entry_time ASC
            """, nativeQuery = true)
    List<TradePosition> findAllByTradeId(@Param("tradeId") UUID tradeId);

    @Query(value = """
            SELECT *
            FROM trade_positions tp
            WHERE tp.trade_id = :tradeId
              AND tp.status = :status
            ORDER BY tp.entry_time ASC
            """, nativeQuery = true)
    List<TradePosition> findAllByTradeIdAndStatus(
            @Param("tradeId") UUID tradeId,
            @Param("status") String status
    );

    @Query(value = """
            SELECT *
            FROM trade_positions tp
            WHERE tp.asset = :asset
              AND tp.status = :status
            ORDER BY tp.entry_time ASC
            """, nativeQuery = true)
    List<TradePosition> findAllByAssetAndStatus(
            @Param("asset") String asset,
            @Param("status") String status
    );

    @Query(value = """
            SELECT
                tp.account_id AS accountId,
                tp.account_strategy_id AS accountStrategyId,
                COALESCE(SUM(tp.realized_pnl_amount), 0) AS dailyRealizedPnlAmount,
                COALESCE(SUM(tp.entry_quote_qty), 0) AS dailyClosedNotional,
                COUNT(*) AS closedPositionCount,
                COALESCE(SUM(CASE WHEN tp.realized_pnl_amount > 0 THEN 1 ELSE 0 END), 0) AS winPositionCount,
                COALESCE(SUM(CASE WHEN tp.realized_pnl_amount < 0 THEN 1 ELSE 0 END), 0) AS lossPositionCount,
                COALESCE(SUM(CASE WHEN tp.realized_pnl_amount = 0 THEN 1 ELSE 0 END), 0) AS breakevenPositionCount
            FROM trade_positions tp
            WHERE tp.status = 'CLOSED'
              AND tp.exit_time >= :startDateTime
              AND tp.exit_time < :endDateTime
            GROUP BY tp.account_id, tp.account_strategy_id
            """, nativeQuery = true)
    List<TradePositionDailyAggregateProjection> findDailyClosedPositionAggregates(
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime
    );

}