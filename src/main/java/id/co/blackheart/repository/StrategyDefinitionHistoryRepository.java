package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyDefinitionHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read access to the spec mutation audit trail.
 *
 * <p>This repository is append-only by convention — writes happen exclusively
 * through StrategyDefinitionService.recordHistory() in the same transaction
 * as the underlying mutation. Do not expose update/delete operations here.
 */
@Repository
public interface StrategyDefinitionHistoryRepository
        extends JpaRepository<StrategyDefinitionHistory, UUID> {

    /** Most recent first — operator browsing history of a strategy. */
    List<StrategyDefinitionHistory> findByStrategyCodeOrderByChangedAtDesc(String strategyCode);

    /** Paged variant for long histories. */
    Page<StrategyDefinitionHistory> findByStrategyCodeOrderByChangedAtDesc(
            String strategyCode, Pageable pageable);

    /** Used by forensic-replay: "what spec was active at time T?". */
    List<StrategyDefinitionHistory> findByStrategyDefinitionIdAndChangedAtLessThanEqualOrderByChangedAtDesc(
            UUID strategyDefinitionId, LocalDateTime cutoff);

    /** Operator audit by user. */
    Page<StrategyDefinitionHistory> findByChangedByUserIdOrderByChangedAtDesc(
            UUID userId, Pageable pageable);
}
