package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BacktestRunRepository extends JpaRepository<BacktestRun, UUID> {
}
