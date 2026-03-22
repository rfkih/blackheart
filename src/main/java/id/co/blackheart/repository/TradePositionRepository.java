package id.co.blackheart.repository;

import id.co.blackheart.model.TradePosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
            SELECT *
            FROM trade_positions tp
            WHERE tp.user_id = :userId
              AND tp.user_strategy_id = :userStrategyId
              AND tp.status = :status
            ORDER BY tp.entry_time ASC
            """, nativeQuery = true)
    List<TradePosition> findAllByUserStrategyAndStatus(
            @Param("userId") UUID userId,
            @Param("userStrategyId") UUID userStrategyId,
            @Param("status") String status
    );

    @Query(value = """
            SELECT COUNT(1)
            FROM trade_positions tp
            WHERE tp.trade_id = :tradeId
              AND tp.status = :status
            """, nativeQuery = true)
    long countByTradeIdAndStatus(
            @Param("tradeId") UUID tradeId,
            @Param("status") String status
    );
}