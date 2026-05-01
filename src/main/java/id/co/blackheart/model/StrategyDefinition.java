package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Strategy registry. One row per strategy code (LSR, VCB, VBO, BBR, ENGINE-driven new strategies).
 *
 * <p>Two flavors of strategies live in this table side-by-side:
 * <ul>
 *   <li>{@code archetype = "LEGACY_JAVA"} — hand-coded Java executor (LsrStrategyService,
 *       VcbStrategyService, VboStrategyService, etc.). {@code specJsonb} is null.
 *       The factory routes these to the matching Java executor by strategy code.</li>
 *   <li>{@code archetype = "<archetype-name>"} — spec-driven, runs through StrategyEngine.
 *       {@code specJsonb} holds the full strategy specification.
 *       See {@code docs/PARAMETRIC_ENGINE_BLUEPRINT.md} for the spec schema.</li>
 * </ul>
 *
 * <p>Per-account parameter overrides on top of the strategy's defaults live in the
 * {@link StrategyParam} table, keyed by {@code account_strategy_id}.
 */
@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "strategy_definition")
public class StrategyDefinition extends BaseEntity {

    @Id
    @Column(name = "strategy_definition_id", nullable = false, updatable = false)
    private UUID strategyDefinitionId;

    @Column(name = "strategy_code", nullable = false, unique = true, length = 100)
    private String strategyCode;

    @Column(name = "strategy_name", nullable = false, length = 200)
    private String strategyName;

    @Column(name = "strategy_type", nullable = false, length = 100)
    private String strategyType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * Engine handler name. {@code "LEGACY_JAVA"} for hand-coded strategies (router falls
     * through to the matching Java executor by strategy code); other values name an
     * archetype registered with the StrategyEngine (e.g. {@code "mean_reversion_oscillator"}).
     */
    @Column(name = "archetype", nullable = false, length = 64)
    @Builder.Default
    private String archetype = "LEGACY_JAVA";

    /**
     * Archetype schema version. Engine refuses to evaluate specs whose version it does
     * not support; SpecVersionAdapter migrations handle forward-compat for older specs.
     */
    @Column(name = "archetype_version", nullable = false)
    @Builder.Default
    private Integer archetypeVersion = 1;

    /**
     * Full strategy spec for spec-driven strategies. {@code null} for {@code LEGACY_JAVA}.
     * Schema documented in {@code docs/PARAMETRIC_ENGINE_BLUEPRINT.md} §7.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_jsonb", columnDefinition = "jsonb")
    private Map<String, Object> specJsonb;

    /**
     * Top-level spec envelope version (separate from {@code archetypeVersion}).
     * Bumps when the outer spec shape changes (e.g. new top-level fields).
     */
    @Column(name = "spec_schema_version", nullable = false)
    @Builder.Default
    private Integer specSchemaVersion = 1;

    /**
     * Soft-delete flag. Historical backtests / promotion log rows still resolve the
     * strategy by id; live orchestration filters on {@code is_deleted = false}.
     */
    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
