package id.co.blackheart.repository;

import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HistoricalBackfillJobRepository extends JpaRepository<HistoricalBackfillJob, UUID> {

    /**
     * Top-N most recent jobs across all statuses. Pass a {@code PageRequest}
     * with the row cap to {@code Pageable.size}. Returns a {@link List}
     * deliberately — there's no useful "page metadata" for a top-N triage
     * view, only a row cap.
     */
    @Query(value = """
    SELECT j FROM HistoricalBackfillJob j
    ORDER BY j.createdAt DESC
    """)
    List<HistoricalBackfillJob> findTopOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Top-N most recent jobs filtered by status. See
     * {@link #findTopOrderByCreatedAtDesc} for the rationale on returning
     * {@link List} instead of {@link org.springframework.data.domain.Page}.
     */
    @Query(value = """
    SELECT j FROM HistoricalBackfillJob j
    WHERE j.status = :status
    ORDER BY j.createdAt DESC
    """)
    List<HistoricalBackfillJob> findTopByStatusOrderByCreatedAtDesc(
            @Param("status") JobStatus status,
            Pageable pageable
    );

    /**
     * Active = PENDING or RUNNING. The orchestrator checks this before
     * submitting a duplicate job for the same (symbol, interval, type).
     */
    @Query(value = """
    SELECT j FROM HistoricalBackfillJob j
    WHERE j.status IN ('PENDING','RUNNING')
    ORDER BY j.createdAt DESC
    """)
    List<HistoricalBackfillJob> findActive();

    @Query(value = """
    SELECT COUNT(j) FROM HistoricalBackfillJob j
    WHERE j.status IN ('PENDING','RUNNING')
    """)
    long countActive();
}
