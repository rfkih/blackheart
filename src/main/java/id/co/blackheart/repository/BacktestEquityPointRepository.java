package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestEquityPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BacktestEquityPointRepository extends JpaRepository<BacktestEquityPoint, UUID> {
}
