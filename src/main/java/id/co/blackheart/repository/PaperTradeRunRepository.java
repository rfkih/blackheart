package id.co.blackheart.repository;

import id.co.blackheart.model.PaperTradeRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PaperTradeRunRepository extends JpaRepository<PaperTradeRun, UUID> {

    List<PaperTradeRun> findByAccountStrategyIdOrderByCreatedTimeDesc(UUID accountStrategyId);

    long countByAccountStrategyIdAndCreatedTimeAfter(UUID accountStrategyId, LocalDateTime since);
}
