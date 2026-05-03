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
     * Promotion-pipeline guard (V15+). When TRUE, the live executor records
     * decisions into {@code paper_trade_run} instead of placing real orders
     * with {@code BinanceClientService}. Default FALSE so pre-V15 strategies
     * keep current behavior.
     *
     * <p>State semantics:
     * <ul>
     *   <li>{@code enabled=false}                                 → not running (RESEARCH)</li>
     *   <li>{@code enabled=true, simulated=true}                  → live signals, paper orders (PAPER_TRADE)</li>
     *   <li>{@code enabled=true, simulated=false}                 → real capital (PROMOTED/ACTIVE)</li>
     * </ul>
     *
     * <p>Flip via {@code POST /api/v1/strategy/{id}/promote} — the controller
     * writes a {@code strategy_promotion_log} row in the same transaction so
     * every change is auditable.
     */
    @Column(name = "simulated", nullable = false)
    @Builder.Default
    private Boolean simulated = Boolean.FALSE;

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

    /**
     * Regime-aware entry gate (V43). When {@code true}, live entries are blocked
     * unless the current bar's {@code trend_regime} / {@code volatility_regime}
     * are in the allowed sets below. Default {@code false} preserves all
     * pre-V43 behaviour.
     */
    @Column(name = "regime_gate_enabled", nullable = false)
    @Builder.Default
    private Boolean regimeGateEnabled = Boolean.FALSE;

    /** Comma-separated {@code trend_regime} values allowed for new entries (e.g. "BULL,NEUTRAL"). Null = any. */
    @Column(name = "allowed_trend_regimes", length = 100)
    private String allowedTrendRegimes;

    /** Comma-separated {@code volatility_regime} values allowed for new entries (e.g. "NORMAL,LOW"). Null = any. */
    @Column(name = "allowed_volatility_regimes", length = 100)
    private String allowedVolatilityRegimes;

    /**
     * Kelly/bankroll sizing (V45). When {@code true}, {@link KellySizingService}
     * applies a PSR-discounted half-Kelly multiplier to the entry size before
     * vol-targeting. Default {@code false} preserves all pre-V45 behaviour.
     */
    @Column(name = "kelly_sizing_enabled", nullable = false)
    @Builder.Default
    private Boolean kellySizingEnabled = Boolean.FALSE;

    /**
     * Hard cap on the Kelly fraction regardless of what the formula computes.
     * Default 0.25 (25% of intended size) protects against over-leverage on
     * noisy backtests. Operator can raise per-strategy via admin UI.
     */
    @Column(name = "kelly_max_fraction", nullable = false, precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal kellyMaxFraction = new BigDecimal("0.2500");

}