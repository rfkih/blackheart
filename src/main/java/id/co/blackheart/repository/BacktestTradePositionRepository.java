package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestTradePosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BacktestTradePositionRepository extends JpaRepository<BacktestTradePosition, UUID> {


    @Query(value = """
    SELECT *
    FROM backtest_trade_position btp
    WHERE btp.trade_id = :tradeId
      AND btp.status = :status
    ORDER BY btp.entry_time ASC
    """, nativeQuery = true)
    List<BacktestTradePosition> findAllByTradeIdAndStatus(UUID tradeId, String status);

    
    @Query(value = """
        SELECT *
        FROM backtest_trade_position btp
        WHERE btp.trade_position_id = :tradePositionId
        """, nativeQuery = true)
    Optional<BacktestTradePosition> findByTradePositionId(UUID tradePositionId);

    @Query(value = """
        SELECT *
        FROM backtest_trade_position btp
        WHERE btp.trade_id = :tradeId
        ORDER BY btp.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTradePosition> findAllByTradeId(UUID tradeId);

    @Query(value = """
        SELECT *
        FROM backtest_trade_position btp
        WHERE btp.backtest_run_id = :backtestRunId
        ORDER BY btp.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTradePosition> findAllByBacktestRunId(UUID backtestRunId);

    @Query(value = """
        SELECT *
        FROM backtest_trade_position btp
        WHERE btp.backtest_run_id = :backtestRunId
          AND btp.status = 'OPEN'
        ORDER BY btp.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTradePosition> findAllOpenPositionsByRunId(UUID backtestRunId);

    @Query(value = """
        SELECT *
        FROM backtest_trade_position btp
        WHERE btp.trade_id = :tradeId
          AND btp.status = 'OPEN'
        ORDER BY btp.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTradePosition> findAllOpenPositionsByTradeId(UUID tradeId);

    @Query(value = """
        SELECT *
        FROM backtest_trade_position btp
        WHERE btp.backtest_run_id = :backtestRunId
          AND btp.asset = :asset
          AND btp.status = 'OPEN'
        ORDER BY btp.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTradePosition> findAllOpenPositionsByRunIdAndAsset(
            UUID backtestRunId,
            String asset
    );

    @Query(value = """
        SELECT *
        FROM backtest_trade_position btp
        WHERE btp.trade_id = :tradeId
          AND btp.position_role = :positionRole
        ORDER BY btp.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTradePosition> findAllByTradeIdAndPositionRole(
            UUID tradeId,
            String positionRole
    );
}