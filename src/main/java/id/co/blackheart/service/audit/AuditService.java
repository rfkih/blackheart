package id.co.blackheart.service.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.AuditEvent;
import id.co.blackheart.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service-layer audit recorder. Callers invoke {@link #record} from inside
 * the same {@code @Transactional} method that performs the mutation — if
 * the mutation rolls back, the audit row rolls back too. No "we logged it
 * but it didn't happen" mismatches.
 *
 * <p>Writes never throw to the caller. A failure to serialize a snapshot
 * or persist the audit row is logged at WARN level but not propagated —
 * the audit log is forensic infrastructure, not the source of truth, and
 * a serializer bug shouldn't break user-facing flows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persist an audit event in the current transaction. {@code before} and
     * {@code after} can be any object the {@link ObjectMapper} can serialize
     * (typically the entity itself); they're converted to JSON snapshots.
     * Pass {@code null} for either when not applicable (creation has no
     * before; deletion has no after).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            UUID actorUserId,
            String action,
            String entityType,
            UUID entityId,
            Object before,
            Object after,
            String reason
    ) {
        try {
            AuditEvent event = AuditEvent.builder()
                    .auditEventId(UUID.randomUUID())
                    .actorUserId(actorUserId)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .beforeData(toJsonNode(before))
                    .afterData(toJsonNode(after))
                    .reason(reason)
                    .createdAt(LocalDateTime.now())
                    .build();
            auditEventRepository.save(event);
        } catch (RuntimeException e) { // NOSONAR java:S2221 — see rationale in javadoc
            // Don't break the user flow on audit failure — surface it loudly
            // for ops to investigate. The mutation that the caller already
            // performed will still commit in the surrounding transaction.
            // Catching RuntimeException broadly is deliberate: any of
            // Jackson serialization, JPA persist, transaction-manager, or
            // future driver failures should be logged and swallowed here,
            // never propagated to the user. The audit log is forensic
            // infrastructure — it must never block a write that already
            // succeeded in the surrounding transaction.
            log.warn("Failed to record audit event | action={} entityType={} entityId={} actor={}",
                    action, entityType, entityId, actorUserId, e);
        }
    }

    /** Convenience overload — most call sites don't need a free-text reason. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            UUID actorUserId,
            String action,
            String entityType,
            UUID entityId,
            Object before,
            Object after
    ) {
        record(actorUserId, action, entityType, entityId, before, after, null);
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) return null;
        return objectMapper.valueToTree(value);
    }
}
