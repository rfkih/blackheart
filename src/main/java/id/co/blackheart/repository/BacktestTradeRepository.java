package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BacktestTradeRepository extends JpaRepository<BacktestTrade, UUID> {

    @Query(
            value = """
        SELECT *
        FROM backtest_trade
        WHERE backtest_run_id = :backtestRunId
          AND status = :status
        """,
            nativeQuery = true
    )
    List<BacktestTrade> findByBacktestRunIdAndStatus(
            @Param("backtestRunId") UUID backtestRunId,
            @Param("status") String status
    );
}