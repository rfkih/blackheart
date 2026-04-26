package id.co.blackheart.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only audit trail for security-sensitive mutations: who changed
 * what, when, with the before/after state captured for forensics.
 *
 * <p>Writes happen in the service layer (not via AOP) so the entity_id
 * and the JSON snapshots are always meaningful — AOP has no way to know
 * whether the loaded entity is the one we care about. Every write is in
 * the same transaction as the mutation it audits, so a rollback on the
 * mutation rolls back the audit row too (you never get a "we logged this
 * change but the change didn't happen" lie).
 */
@Entity
@Table(name = "audit_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @Column(name = "audit_event_id", nullable = false, updatable = false)
    private UUID auditEventId;

    /** UUID of the user that initiated the action (from JWT). */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    /**
     * Verb describing what happened. Keep as a stable enumeration in code
     * (e.g. "STRATEGY_CREATED", "STRATEGY_DELETED", "ACCOUNT_RISK_UPDATED",
     * "CREDENTIALS_ROTATED") so dashboards and SIEM filters can match.
     */
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    /**
     * Logical entity type the action targeted (e.g. "AccountStrategy",
     * "Account"). Useful for filtering in audit queries.
     */
    @Column(name = "entity_type", length = 100)
    private String entityType;

    /** Primary key of the entity that changed, when applicable. */
    @Column(name = "entity_id")
    private UUID entityId;

    /**
     * Pre-mutation snapshot. Null on creation events. Stored as JSONB so
     * fields can be queried directly with Postgres operators.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data")
    private JsonNode beforeData;

    /**
     * Post-mutation snapshot. Null on deletion events.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data")
    private JsonNode afterData;

    /**
     * Optional free-text context (e.g. "Cap was lowered after a Slack
     * conversation about over-exposure"). Operators can attach a reason
     * via the request body when relevant.
     */
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
