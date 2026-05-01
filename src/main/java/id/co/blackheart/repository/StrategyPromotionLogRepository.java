package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyPromotionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyPromotionLogRepository extends JpaRepository<StrategyPromotionLog, UUID> {

    /** Most-recent promotion row for the given strategy — defines the current state. */
    Optional<StrategyPromotionLog> findFirstByAccountStrategyIdOrderByCreatedTimeDesc(UUID accountStrategyId);

    List<StrategyPromotionLog> findByAccountStrategyIdOrderByCreatedTimeDesc(UUID accountStrategyId);

    /** Cross-strategy feed for the /research dashboard — newest promotions across all strategies. */
    @Query("SELECT l FROM StrategyPromotionLog l ORDER BY l.createdTime DESC")
    List<StrategyPromotionLog> findRecent(Pageable pageable);
}
