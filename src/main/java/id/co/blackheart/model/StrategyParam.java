package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Unified per-account-strategy parameter overrides — replaces the legacy
 * per-strategy tables (lsr_strategy_param, vcb_strategy_param, vbo_strategy_param).
 *
 * <p>One row per {@code account_strategy_id}, serving every strategy code
 * (legacy hand-coded Java strategies AND spec-driven engine strategies).
 * A missing row means "use defaults" — the service layer merges
 * {@code paramOverrides} on top of the strategy's default param map at read time.
 *
 * <p>For LEGACY_JAVA strategies, the override shape mirrors the strategy class's
 * own Params DTO (e.g. {@code {"adxThreshold": 25}}). For spec-driven strategies,
 * the override shape mirrors the spec's archetype-defined parameter schema.
 *
 * <p>See {@code docs/PARAMETRIC_ENGINE_BLUEPRINT.md} §16.2 for the full design.
 */
@Entity
@Table(name = "strategy_param")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyParam extends BaseEntity {

    /** Same as the account_strategy_id FK — serves as PK for 1-to-1 semantics. */
    @Id
    @Column(name = "account_strategy_id", nullable = false, updatable = false)
    private UUID accountStrategyId;

    /**
     * Partial override map. Keys and value types depend on the strategy:
     * for LEGACY_JAVA strategies the keys mirror the strategy's Params DTO fields;
     * for spec-driven strategies the keys are validated against the archetype's
     * parameter schema before write.
     * Stored as {@code jsonb} in PostgreSQL.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_overrides", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> paramOverrides = new HashMap<>();

    /**
     * Optimistic-lock version. Prevents concurrent PUT/PATCH from silently overwriting each other.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
