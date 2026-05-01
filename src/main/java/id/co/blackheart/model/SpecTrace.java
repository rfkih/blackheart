package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-evaluation audit trail of {@code StrategyEngine} decisions.
 *
 * <p>Backtest writes densely (one row per active strategy per bar). Live samples
 * at {@code engine.trace.sample-rate} (default 1%). Used by:
 * <ul>
 *   <li>{@code SpecTraceViewer} UI to render decision trees</li>
 *   <li>{@code EngineMetrics} to compute per-strategy error rates and trip
 *       the spec-error kill switch</li>
 *   <li>Operators for forensic replay of past decisions</li>
 * </ul>
 *
 * <p>See {@code docs/PARAMETRIC_ENGINE_BLUEPRINT.md} §14, §16.4 for the full design.
 */
@Entity
@Table(name = "spec_trace")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecTrace {

    @Id
    @Column(name = "trace_id", nullable = false, updatable = false)
    private UUID traceId;

    /** Null for live traces; set for backtest traces. */
    @Column(name = "backtest_run_id")
    private UUID backtestRunId;

    /** Null for backtest-only specs that aren't bound to an account_strategy. */
    @Column(name = "account_strategy_id")
    private UUID accountStrategyId;

    @Column(name = "strategy_code", nullable = false, length = 100)
    private String strategyCode;

    @Column(name = "bar_time", nullable = false)
    private LocalDateTime barTime;

    /** ENTRY_LONG, ENTRY_SHORT, MANAGE, EXIT, etc. */
    @Column(name = "phase", nullable = false, length = 32)
    private String phase;

    /**
     * Exact spec used by this evaluation. Captured at the start of evaluate() so
     * concurrent hot-reload cannot affect this row. Allows replay: given
     * {@code (specSnapshot, features at barTime)}, the engine MUST produce the
     * same decision.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> specSnapshot;

    /**
     * Per-rule trace array. Each element:
     * {@code { "index": 0, "type": "rsi_extreme", "result": true, "value": 22.4 }}.
     * Order matches the spec's entry/exit rules so SpecTraceViewer can highlight
     * which rule failed in a NO_TRADE decision.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> rules;

    /** OPEN_LONG, OPEN_SHORT, CLOSE, NO_TRADE, etc. */
    @Column(name = "decision", nullable = false, length = 64)
    private String decision;

    /** Human-readable explanation. e.g. "rule[2] failed: candle_pattern not bullish_reversal". */
    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    /** Microseconds. Used by EngineMetrics for performance regression alerting. */
    @Column(name = "eval_latency_us")
    private Integer evalLatencyUs;

    /**
     * When non-null, the engine caught an exception during evaluation and returned
     * NO_TRADE. EngineMetrics aggregates these to detect spec bugs and trip the
     * per-strategy kill switch on error-rate threshold.
     */
    @Column(name = "error_class", length = 200)
    private String errorClass;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_time", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdTime = LocalDateTime.now();
}
