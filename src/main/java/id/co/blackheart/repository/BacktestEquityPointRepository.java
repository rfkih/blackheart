package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestEquityPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BacktestEquityPointRepository extends JpaRepository<BacktestEquityPoint, UUID> {

    @Query(value = """
            SELECT * FROM backtest_equity_point
            WHERE backtest_run_id = :backtestRunId
            ORDER BY equity_date ASC
            """, nativeQuery = true)
    List<BacktestEquityPoint> findByBacktestRunIdOrderByEquityDateAsc(
            @Param("backtestRunId") UUID backtestRunId
    );
}
