package id.co.blackheart.service.strategy;

import id.co.blackheart.model.StrategyDefinition;
import id.co.blackheart.model.StrategyDefinitionHistory;
import id.co.blackheart.repository.StrategyDefinitionHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.UUID;

/**
 * Transactional history writer for {@link StrategyDefinition} mutations.
 * Mirrors {@link id.co.blackheart.service.audit.AuditService}'s contract:
 * every write happens in the caller's transaction (so a rollback on the
 * spec mutation rolls back the history row too), and failures here never
 * propagate — forensic plumbing must not break the user flow.
 *
 * <p>Each call appends one row to {@code strategy_definition_history}.
 * Operation values are {@code INSERT}, {@code UPDATE}, {@code DELETE},
 * {@code UPGRADE} — the DB-level CHECK constraint refuses anything else.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyDefinitionHistoryService {

    public static final String OP_INSERT = "INSERT";
    public static final String OP_UPDATE = "UPDATE";
    public static final String OP_DELETE = "DELETE";
    public static final String OP_UPGRADE = "UPGRADE";

    private final StrategyDefinitionHistoryRepository historyRepository;

    /**
     * Persist a history row in the current transaction.
     *
     * @param def              the strategy definition AT THE TIME OF MUTATION
     *                         (post-mutation for INSERT/UPDATE/UPGRADE; the row
     *                         being deleted for DELETE).
     * @param operation        one of the {@code OP_*} constants.
     * @param changedByUserId  the actor; {@code null} for system-initiated changes.
     * @param reason           free-form rationale; nullable.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(StrategyDefinition def,
                       String operation,
                       UUID changedByUserId,
                       String reason) {
        try {
            StrategyDefinitionHistory row = StrategyDefinitionHistory.builder()
                    .historyId(UUID.randomUUID())
                    .strategyCode(def.getStrategyCode())
                    .strategyDefinitionId(def.getStrategyDefinitionId())
                    .archetype(def.getArchetype())
                    .archetypeVersion(def.getArchetypeVersion())
                    .specJsonb(def.getSpecJsonb() == null ? null : new HashMap<>(def.getSpecJsonb()))
                    .specSchemaVersion(def.getSpecSchemaVersion() == null ? 1 : def.getSpecSchemaVersion())
                    .operation(operation)
                    .changedByUserId(changedByUserId)
                    .changedAt(LocalDateTime.now())
                    .changeReason(reason)
                    .build();
            historyRepository.save(row);
        } catch (RuntimeException e) { // NOSONAR java:S2221 — forensic plumbing, mirror AuditService contract
            log.warn("Failed to record strategy_definition_history | op={} strategyCode={} defId={} actor={}",
                    operation, def.getStrategyCode(), def.getStrategyDefinitionId(), changedByUserId, e);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(StrategyDefinition def, String operation, UUID changedByUserId) {
        record(def, operation, changedByUserId, null);
    }
}
