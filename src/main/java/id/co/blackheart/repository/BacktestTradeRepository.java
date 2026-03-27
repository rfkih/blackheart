package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BacktestTradeRepository extends JpaRepository<BacktestTrade, UUID> {

    @Query(value = """
        SELECT *
        FROM backtest_trade bt
        WHERE bt.backtest_trade_id = :backtestTradeId
        """, nativeQuery = true)
    Optional<BacktestTrade> findByTradeId(UUID backtestTradeId);

    @Query(value = """
        SELECT *
        FROM backtest_trade bt
        WHERE bt.backtest_run_id = :backtestRunId
        ORDER BY bt.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTrade> findAllByBacktestRunId(UUID backtestRunId);

    @Query(value = """
        SELECT *
        FROM backtest_trade bt
        WHERE bt.backtest_run_id = :backtestRunId
          AND bt.status = :status
        ORDER BY bt.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTrade> findAllByBacktestRunIdAndStatus(UUID backtestRunId, String status);

    @Query(value = """
        SELECT *
        FROM backtest_trade bt
        WHERE bt.backtest_run_id = :backtestRunId
          AND bt.asset = :asset
          AND bt.user_strategy_id = :userStrategyId
        ORDER BY bt.entry_time DESC
        """, nativeQuery = true)
    List<BacktestTrade> findAllByRunIdAndAssetAndUserStrategyId(
            UUID backtestRunId,
            String asset,
            Long userStrategyId
    );

    @Query(value = """
        SELECT EXISTS (
            SELECT 1
            FROM backtest_trade bt
            WHERE bt.backtest_run_id = :backtestRunId
              AND bt.user_strategy_id = :userStrategyId
              AND bt.asset = :asset
              AND bt.status IN (:statuses)
        )
        """, nativeQuery = true)
    boolean existsByBacktestRunIdAndUserStrategyIdAndAssetAndStatusIn(
            UUID backtestRunId,
            Long userStrategyId,
            String asset,
            Collection<String> statuses
    );

    @Query(value = """
        SELECT *
        FROM backtest_trade bt
        WHERE bt.backtest_run_id = :backtestRunId
          AND bt.user_strategy_id = :userStrategyId
          AND bt.asset = :asset
          AND bt.status IN ('OPEN', 'PARTIALLY_CLOSED')
        ORDER BY bt.entry_time DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<BacktestTrade> findLatestActiveTrade(
            UUID backtestRunId,
            UUID userStrategyId,
            String asset
    );

    @Query(value = """
        SELECT *
        FROM backtest_trade bt
        WHERE bt.backtest_run_id = :backtestRunId
        ORDER BY bt.created_time DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<BacktestTrade> findLatestByBacktestRunId(UUID backtestRunId);
}