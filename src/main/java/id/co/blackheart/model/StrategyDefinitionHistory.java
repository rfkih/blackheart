package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit log of every spec mutation on {@link StrategyDefinition}.
 * Written transactionally with the mutation by StrategyDefinitionService.
 *
 * <p>Critical for: reproducing past trade decisions (which spec was active at time T?),
 * diagnosing behavior shifts (when did the spec last change?), and rolling back bad
 * spec changes (revert to a prior history_id).
 *
 * <p>See {@code docs/PARAMETRIC_ENGINE_BLUEPRINT.md} §16.3 for the full design.
 */
@Entity
@Table(name = "strategy_definition_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyDefinitionHistory {

    @Id
    @Column(name = "history_id", nullable = false, updatable = false)
    private UUID historyId;

    @Column(name = "strategy_code", nullable = false, length = 100)
    private String strategyCode;

    @Column(name = "strategy_definition_id", nullable = false)
    private UUID strategyDefinitionId;

    @Column(name = "archetype", nullable = false, length = 64)
    private String archetype;

    @Column(name = "archetype_version", nullable = false)
    private Integer archetypeVersion;

    /**
     * Snapshot of the spec AT THE TIME OF MUTATION. For UPDATE operations this is the
     * NEW value; the old value lives in the previous history row for the same
     * {@code strategyDefinitionId}.
     * Null for LEGACY_JAVA strategy mutations (no spec body to capture).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_jsonb", columnDefinition = "jsonb")
    private Map<String, Object> specJsonb;

    @Column(name = "spec_schema_version", nullable = false)
    @Builder.Default
    private Integer specSchemaVersion = 1;

    /**
     * One of {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code UPGRADE}.
     * DB-level CHECK constraint enforces this set.
     */
    @Column(name = "operation", nullable = false, length = 16)
    private String operation;

    /** Null when the change was system-initiated (e.g. version migration). */
    @Column(name = "changed_by_user_id")
    private UUID changedByUserId;

    @Column(name = "changed_at", nullable = false)
    @Builder.Default
    private LocalDateTime changedAt = LocalDateTime.now();

    @Column(name = "change_reason", columnDefinition = "TEXT")
    private String changeReason;
}
