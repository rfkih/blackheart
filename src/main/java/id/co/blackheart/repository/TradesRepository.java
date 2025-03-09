package id.co.blackheart.repository;

import id.co.blackheart.model.Trades;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TradesRepository extends JpaRepository<Trades, Long> {
    Optional<Trades> findByUserIdAndAssetAndIsActiveAndTradePlanAndAction(Long userId, String asset, String isActive, String tradePlan, String Action);
}

