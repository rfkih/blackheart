package id.co.blackheart.repository;

import id.co.blackheart.model.AlertEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    Page<AlertEvent> findByKindOrderByCreatedAtDesc(String kind, Pageable pageable);

    Page<AlertEvent> findBySeverityOrderByCreatedAtDesc(String severity, Pageable pageable);

    /**
     * Filterable feed for the admin /alerts inbox. All filter args are
     * nullable — null means "no filter on this dimension". `since` is
     * inclusive. Suppressed rows are included by default so the operator
     * can see flap rate; the UI can toggle them off via the param.
     *
     * Native query required: PostgreSQL cannot infer the type of a null
     * literal in JPQL's `? IS NULL` pattern for typed columns (timestamp).
     * CAST gives the driver an explicit type so the prepared-statement plan
     * compiles even when since=null.
     */
    @Query(value = """
            SELECT * FROM alert_event a
            WHERE (:severity IS NULL OR a.severity = :severity)
              AND (:kind IS NULL OR a.kind = :kind)
              AND (CAST(:since AS timestamp) IS NULL OR a.created_at >= CAST(:since AS timestamp))
              AND (CAST(:includeSuppressed AS boolean) = true OR a.suppressed = false)
            ORDER BY a.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM alert_event a
            WHERE (:severity IS NULL OR a.severity = :severity)
              AND (:kind IS NULL OR a.kind = :kind)
              AND (CAST(:since AS timestamp) IS NULL OR a.created_at >= CAST(:since AS timestamp))
              AND (CAST(:includeSuppressed AS boolean) = true OR a.suppressed = false)
            """,
            nativeQuery = true)
    Page<AlertEvent> findFiltered(
            @Param("severity") String severity,
            @Param("kind") String kind,
            @Param("since") LocalDateTime since,
            @Param("includeSuppressed") boolean includeSuppressed,
            Pageable pageable);

    /**
     * Count of non-suppressed alert rows since the given cutoff. Drives
     * the header badge — "since" is the user's last-seen marker (stored
     * client-side; falls back to "last 24h" on first visit).
     */
    @Query("""
            SELECT COUNT(a) FROM AlertEvent a
            WHERE a.suppressed = false
              AND (:since IS NULL OR a.createdAt >= :since)
              AND (:minSeverityRank IS NULL OR
                   CASE a.severity
                     WHEN 'CRITICAL' THEN 3
                     WHEN 'WARN' THEN 2
                     WHEN 'INFO' THEN 1
                     ELSE 0
                   END >= :minSeverityRank)
            """)
    long countUnread(
            @Param("since") LocalDateTime since,
            @Param("minSeverityRank") Integer minSeverityRank);
}
