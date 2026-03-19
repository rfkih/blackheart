package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestTrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BacktestTradeRepository extends JpaRepository<BacktestTrade, UUID> {
}

