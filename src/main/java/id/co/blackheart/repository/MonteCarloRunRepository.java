package id.co.blackheart.repository;

import id.co.blackheart.model.MonteCarloRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MonteCarloRunRepository extends JpaRepository<MonteCarloRun, UUID> {

    List<MonteCarloRun> findByBacktestRunIdOrderByCreatedTimeDesc(UUID backtestRunId);
}