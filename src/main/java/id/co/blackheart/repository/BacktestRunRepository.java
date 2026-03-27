package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BacktestRunRepository extends JpaRepository<BacktestRun, UUID> {

    @Query(value = """
        SELECT *
        FROM backtest_run br
        WHERE br.backtest_run_id = :backtestRunId
        """, nativeQuery = true)
    Optional<BacktestRun> findByBacktestRunId(UUID backtestRunId);

    @Query(value = """
        SELECT *
        FROM backtest_run br
        WHERE br.status = :status
        ORDER BY br.created_time DESC
        """, nativeQuery = true)
    List<BacktestRun> findAllByStatus(String status);

    @Query(value = """
        SELECT *
        FROM backtest_run br
        WHERE br.user_strategy_id = :userStrategyId
        ORDER BY br.created_time DESC
        """, nativeQuery = true)
    List<BacktestRun> findAllByUserStrategyId(Long userStrategyId);
}