package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "account_strategy")
public class AccountStrategy extends BaseEntity {

    @Id
    @Column(name = "account_strategy_id", nullable = false, updatable = false)
    private UUID accountStrategyId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "strategy_definition_id", nullable = false)
    private UUID strategyDefinitionId;

    @Column(name = "strategy_code", nullable = false, length = 100)
    private String strategyCode;

    @Column(name = "preset_name", length = 80)
    private String presetName;

    @Column(name = "symbol", nullable = false, length = 30)
    private String symbol;

    @Column(name = "interval_name", nullable = false, length = 20)
    private String intervalName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "allow_long", nullable = false)
    private Boolean allowLong;

    @Column(name = "allow_short", nullable = false)
    private Boolean allowShort;

    @Column(name = "max_open_positions", nullable = false)
    private Integer maxOpenPositions;

    @Column(name = "capital_allocation_pct", nullable = false, precision = 8, scale = 4)
    private BigDecimal capitalAllocationPct;

    @Column(name = "priority_order", nullable = false)
    private Integer priorityOrder;

    @Column(name = "current_status", nullable = false, length = 30)
    private String currentStatus;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * Drawdown kill-switch — when the strategy's rolling 30-day drawdown
     * exceeds this percentage, RiskGuardService trips the switch and
     * blocks new entries until manually re-armed. Default 25%; range
     * enforced at the controller layer.
     */
    @Column(name = "dd_kill_threshold_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal ddKillThresholdPct = new BigDecimal("25.00");

    /**
     * Sticky trip state. {@code true} means new entries are blocked for
     * this strategy. Resetting requires an explicit re-arm — we don't
     * auto-clear because the trip itself signals "human, look at this".
     */
    @Column(name = "is_kill_switch_tripped", nullable = false)
    @Builder.Default
    private Boolean isKillSwitchTripped = Boolean.FALSE;

    @Column(name = "kill_switch_tripped_at")
    private LocalDateTime killSwitchTrippedAt;

    /** Human-readable trip reason (e.g. "30-day DD 32.4% exceeded threshold 25%"). */
    @Column(name = "kill_switch_reason", columnDefinition = "TEXT")
    private String killSwitchReason;

}