package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One saved parameter preset for an {@code account_strategy}.
 *
 * <p>V29 redesign: 1:N replaces the previous 1:1 schema. Each row is a named
 * preset; at most one preset per {@code account_strategy_id} is {@code is_active}
 * (enforced by the partial unique index {@code uq_strategy_param_one_active}).
 * The active preset is the one live trading reads. Backtests can target any
 * preset by {@code param_id}, including soft-deleted ones (so historical runs
 * stay reproducible). Soft-deleted presets are hidden from the saved-set listing.
 *
 * <p>The {@code paramOverrides} JSONB shape is identical to the legacy
 * {@code lsr_strategy_param} / {@code vcb_strategy_param} / {@code vbo_strategy_param}
 * shape for those strategies, and identical to the spec-driven param schema for
 * archetype-driven strategies — the unified service is intentionally
 * shape-agnostic.
 */
@Entity
@Table(name = "strategy_param")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyParam extends BaseEntity {

    @Id
    @Column(name = "param_id", nullable = false, updatable = false)
    private UUID paramId;

    @Column(name = "account_strategy_id", nullable = false, updatable = false)
    private UUID accountStrategyId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_overrides", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> paramOverrides = new HashMap<>();

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = false;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * Optional FK to {@code backtest_run.backtest_run_id} — the run whose params
     * seeded this preset. Set by the Re-run-with-params "Save to library" flow;
     * null for presets created via the wizard "Save current" path or the
     * legacy {@code /{lsr,vcb,vbo}-params} shims. {@code ON DELETE SET NULL}
     * at the DB layer so a backtest-run cleanup can't strand the preset.
     */
    @Column(name = "source_backtest_run_id")
    private UUID sourceBacktestRunId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
