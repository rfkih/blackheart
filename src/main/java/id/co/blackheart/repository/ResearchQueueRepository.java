package id.co.blackheart.repository;

import id.co.blackheart.model.ResearchQueueItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchQueueRepository extends JpaRepository<ResearchQueueItem, UUID> {

    /**
     * Lists rows by status filter. {@code statuses} empty → returns all.
     * Ordered by priority asc (lower number = sooner), then created_time asc
     * to match the orchestrator's claim order so the UI mirrors execution.
     */
    @Query("""
            SELECT q FROM ResearchQueueItem q
            WHERE (:strategyCode IS NULL OR q.strategyCode = :strategyCode)
              AND (COALESCE(:statuses, NULL) IS NULL OR q.status IN :statuses)
            ORDER BY q.priority ASC, q.createdTime ASC
            """)
    List<ResearchQueueItem> findFiltered(
            @Param("strategyCode") String strategyCode,
            @Param("statuses") List<String> statuses);
}
