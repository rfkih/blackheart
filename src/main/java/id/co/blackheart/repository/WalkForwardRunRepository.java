package id.co.blackheart.repository;

import id.co.blackheart.model.WalkForwardRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-only repository over {@code walk_forward_run}. The trading JVM
 * never writes here — that's the orchestrator's job — so there are no
 * modifying queries.
 */
@Repository
public interface WalkForwardRunRepository extends JpaRepository<WalkForwardRun, UUID> {

    /**
     * Filterable list for the admin walk-forward viewer. Filters compose
     * additively; null means "no filter on this dimension".
     */
    @Query("""
            SELECT w FROM WalkForwardRun w
            WHERE (:strategyCode IS NULL OR w.strategyCode = :strategyCode)
              AND (:instrument IS NULL OR w.instrument = :instrument)
              AND (:intervalName IS NULL OR w.intervalName = :intervalName)
              AND (:verdict IS NULL OR w.stabilityVerdict = :verdict)
            ORDER BY w.createdTime DESC
            """)
    Page<WalkForwardRun> findFiltered(
            @Param("strategyCode") String strategyCode,
            @Param("instrument") String instrument,
            @Param("intervalName") String intervalName,
            @Param("verdict") String verdict,
            Pageable pageable);

    /**
     * Recent runs scoped to a single (strategy, instrument, interval). Used
     * inline on strategy detail pages — much smaller payload than the full
     * filtered list.
     */
    @Query("""
            SELECT w FROM WalkForwardRun w
            WHERE w.strategyCode = :strategyCode
              AND w.instrument = :instrument
              AND w.intervalName = :intervalName
            ORDER BY w.createdTime DESC
            """)
    List<WalkForwardRun> findRecentForStrategy(
            @Param("strategyCode") String strategyCode,
            @Param("instrument") String instrument,
            @Param("intervalName") String intervalName,
            Pageable pageable);
}
