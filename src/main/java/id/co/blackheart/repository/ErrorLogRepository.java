package id.co.blackheart.repository;

import id.co.blackheart.model.ErrorLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ErrorLogRepository extends JpaRepository<ErrorLog, UUID> {

    /**
     * Filterable feed for the admin /admin/errors inbox. All filter args are
     * nullable — null means "no filter on this dimension". `since` is
     * inclusive on lastSeenAt so a long-running fingerprint with stale
     * occurredAt but recent re-occurrence still surfaces in the cutoff.
     */
    @Query("""
            SELECT e FROM ErrorLog e
            WHERE (:severity IS NULL OR e.severity = :severity)
              AND (:status IS NULL OR e.status = :status)
              AND (:jvm IS NULL OR e.jvm = :jvm)
              AND (:since IS NULL OR e.lastSeenAt >= :since)
            ORDER BY e.lastSeenAt DESC
            """)
    Page<ErrorLog> findFiltered(
            @Param("severity") String severity,
            @Param("status") String status,
            @Param("jvm") String jvm,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    /**
     * Count of currently-open rows (status NEW/INVESTIGATING) at or above the
     * given severity rank. Drives the admin nav badge — operators want to
     * know if anything urgent is unhandled, regardless of when it first fired.
     */
    @Query("""
            SELECT COUNT(e) FROM ErrorLog e
            WHERE e.status IN ('NEW', 'INVESTIGATING')
              AND (:minSeverityRank IS NULL OR
                   CASE e.severity
                     WHEN 'CRITICAL' THEN 4
                     WHEN 'HIGH' THEN 3
                     WHEN 'MEDIUM' THEN 2
                     WHEN 'LOW' THEN 1
                     ELSE 0
                   END >= :minSeverityRank)
            """)
    long countOpen(@Param("minSeverityRank") Integer minSeverityRank);

    /**
     * Find the currently-open row for a fingerprint, if any. Open means
     * {@code status IN ('NEW','INVESTIGATING')}, which is exactly the partial
     * unique index — at most one such row exists per fingerprint.
     */
    @Query("""
            SELECT e FROM ErrorLog e
            WHERE e.fingerprint = :fingerprint
              AND e.status IN ('NEW', 'INVESTIGATING')
            """)
    Optional<ErrorLog> findOpenByFingerprint(@Param("fingerprint") String fingerprint);

    /**
     * Bump occurrence count + last_seen on a known-open row. Done as an
     * UPDATE rather than a load-modify-save round-trip because the appender
     * path is hot — this saves a SELECT and avoids @Version contention when
     * the same error fires from multiple threads at once.
     */
    @Modifying
    @Query("""
            UPDATE ErrorLog e
               SET e.occurrenceCount = e.occurrenceCount + 1,
                   e.lastSeenAt = :seenAt
             WHERE e.errorId = :id
            """)
    int bumpOccurrence(@Param("id") UUID id, @Param("seenAt") LocalDateTime seenAt);

    @Modifying
    @Query("""
            UPDATE ErrorLog e
               SET e.notifiedAt = :notifiedAt,
                   e.notificationChannels = :channels
             WHERE e.errorId = :id
            """)
    int markNotified(@Param("id") UUID id,
                     @Param("notifiedAt") LocalDateTime notifiedAt,
                     @Param("channels") String[] channels);

    /**
     * Operator-driven status flip from the admin inbox. Sets resolvedAt /
     * resolvedBy when transitioning to a terminal status so the next
     * occurrence opens a fresh row (the partial unique index on fingerprint
     * only covers NEW/INVESTIGATING).
     */
    @Modifying
    @Query("""
            UPDATE ErrorLog e
               SET e.status = :status,
                   e.resolvedAt = :resolvedAt,
                   e.resolvedBy = :resolvedBy
             WHERE e.errorId = :id
            """)
    int updateStatus(@Param("id") UUID id,
                     @Param("status") String status,
                     @Param("resolvedAt") LocalDateTime resolvedAt,
                     @Param("resolvedBy") String resolvedBy);
}
