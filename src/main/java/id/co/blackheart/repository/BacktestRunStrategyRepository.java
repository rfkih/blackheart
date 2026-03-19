package id.co.blackheart.repository;


import id.co.blackheart.model.BacktestRunStrategy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BacktestRunStrategyRepository extends JpaRepository<BacktestRunStrategy, UUID> {
}
