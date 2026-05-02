package id.co.blackheart.repository;

import id.co.blackheart.model.SpecTrace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read access to {@link SpecTrace}. Writes are append-only and happen
 * exclusively through SpecTraceLogger.
 *
 * <p>Most queries are time-bounded by index strategy — see V20 migration
 * for the index definitions backing these access patterns.
 */
@Repository
public interface SpecTraceRepository extends JpaRepository<SpecTrace, UUID> {

    /** Full backtest trace, ordered by bar — drives SpecTraceViewer for a backtest run. */
    List<SpecTrace> findByBacktestRunIdOrderByBarTimeAsc(UUID backtestRunId);

    /** Recent live traces for an account_strategy — drives live debugging. */
    Page<SpecTrace> findByAccountStrategyIdAndBarTimeGreaterThanEqualOrderByBarTimeDesc(
            UUID accountStrategyId, LocalDateTime since, Pageable pageable);

    /**
     * Per-strategy error count in a window. Used by EngineMetrics to evaluate
     * the kill-switch error-rate threshold.
     */
    @Query("""
        SELECT COUNT(t) FROM SpecTrace t
        WHERE t.strategyCode = :strategyCode
          AND t.errorClass IS NOT NULL
          AND t.createdTime >= :since
        """)
    long countErrorsByStrategyCodeSince(
            @Param("strategyCode") String strategyCode,
            @Param("since") LocalDateTime since);

    /** Hard delete used by retention policy for live traces (backtest traces are kept). */
    int deleteByAccountStrategyIdIsNotNullAndCreatedTimeLessThan(LocalDateTime cutoff);

    /**
     * Filterable list for the admin SpecTraceViewer. Filters compose
     * additively; null means "no filter on this dimension". Ordered by
     * bar_time DESC for the recent-first viewer behaviour.
     */
    @Query("""
            SELECT t FROM SpecTrace t
            WHERE (:backtestRunId IS NULL OR t.backtestRunId = :backtestRunId)
              AND (:accountStrategyId IS NULL OR t.accountStrategyId = :accountStrategyId)
              AND (:strategyCode IS NULL OR t.strategyCode = :strategyCode)
              AND (:phase IS NULL OR t.phase = :phase)
              AND (:decision IS NULL OR t.decision = :decision)
              AND (:errorsOnly = FALSE OR t.errorClass IS NOT NULL)
            ORDER BY t.barTime DESC
            """)
    Page<SpecTrace> findFiltered(
            @Param("backtestRunId") UUID backtestRunId,
            @Param("accountStrategyId") UUID accountStrategyId,
            @Param("strategyCode") String strategyCode,
            @Param("phase") String phase,
            @Param("decision") String decision,
            @Param("errorsOnly") boolean errorsOnly,
            Pageable pageable);
}
