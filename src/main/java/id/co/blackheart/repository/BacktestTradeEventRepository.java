package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestTradeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BacktestTradeEventRepository extends JpaRepository<BacktestTradeEvent, UUID> {

    @Query(value = """
        SELECT *
        FROM backtest_trade_event bte
        WHERE bte.backtest_run_id = :backtestRunId
        ORDER BY bte.event_time ASC, bte.created_time ASC
        """, nativeQuery = true)
    List<BacktestTradeEvent> findAllByBacktestRunId(UUID backtestRunId);

    @Query(value = """
        SELECT *
        FROM backtest_trade_event bte
        WHERE bte.trade_id = :tradeId
        ORDER BY bte.event_time ASC, bte.created_time ASC
        """, nativeQuery = true)
    List<BacktestTradeEvent> findAllByTradeId(UUID tradeId);

    @Query(value = """
        SELECT *
        FROM backtest_trade_event bte
        WHERE bte.trade_position_id = :tradePositionId
        ORDER BY bte.event_time ASC, bte.created_time ASC
        """, nativeQuery = true)
    List<BacktestTradeEvent> findAllByTradePositionId(UUID tradePositionId);
}