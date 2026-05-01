package id.co.blackheart.repository;

import id.co.blackheart.model.ErrorLog;
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
}
