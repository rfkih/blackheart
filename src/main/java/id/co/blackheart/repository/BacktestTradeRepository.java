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
    List<BacktestTrade> findByBacktestRunIdAndStatus(UUID backtestRunId, String status);
}