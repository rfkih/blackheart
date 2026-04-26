package id.co.blackheart.repository;

import id.co.blackheart.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /** All events for a single actor, newest first. Paginated for the audit UI. */
    Page<AuditEvent> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId, Pageable pageable);

    /** All events for a specific entity (any actor), newest first. */
    Page<AuditEvent> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, UUID entityId, Pageable pageable);
}
