package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backtest_run")
public class BacktestRun extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "backtest_run_id", nullable = false, updatable = false)
    private UUID backtestRunId;

    /**
     * FK → users.user_id. Owner of this backtest run. Populated from the
     * authenticated JWT at submit time. All read paths filter on this column
     * so one user cannot see another user's runs. Nullable for legacy rows
     * created before tenant scoping was added — those rows are invisible to
     * every user and only retained for audit/metrics.
     */
    @Column(name = "user_id")
    private UUID userId;

    /**
     * Default account strategy ID. Used for single-strategy backtests, or as fallback
     * in multi-strategy runs where a strategy code has no entry in strategyAccountStrategyIds.
     * Can be null for ad-hoc backtests.
     */
    @Column(name = "account_strategy_id")
    private UUID accountStrategyId;

    /**
     * Per-strategy account strategy ID mapping for multi-strategy backtests.
     * Key = strategy code, value = accountStrategyId whose saved params to use.
     * Falls back to accountStrategyId for codes not present here.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_account_strategy_ids", columnDefinition = "jsonb")
    private Map<String, UUID> strategyAccountStrategyIds;

    /**
     * Per-strategy pinned strategy_param.param_id. When a strategy code is in
     * this map the run resolves overrides via that exact preset row (soft-deleted
     * rows are still resolvable here, for historical reruns). When absent the
     * run falls back to the active preset for the matching account_strategy.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_param_ids", columnDefinition = "jsonb")
    private Map<String, UUID> strategyParamIds;

    /**
     * Stable strategy identifier used by StrategyExecutorFactory.
     * Example: TREND_PULLBACK_SINGLE_EXIT
     */
    @Column(name = "strategy_code", length = 100, nullable = false)
    private String strategyCode;

    /**
     * Optional display name for reporting/UI.
     */
    @Column(name = "strategy_name", length = 150)
    private String strategyName;

    /**
     * Version of the strategy logic/config used during the run.
     */
    @Column(name = "strategy_version", length = 50)
    private String strategyVersion;

    @Column(name = "asset", length = 30, nullable = false)
    private String asset;

    @Column(name = "interval_name", length = 20, nullable = false)
    private String interval;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "initial_capital", precision = 24, scale = 8, nullable = false)
    private BigDecimal initialCapital;

    @Column(name = "risk_per_trade_pct", precision = 12, scale = 6)
    private BigDecimal riskPerTradePct;

    @Column(name = "fee_pct", precision = 12, scale = 6)
    private BigDecimal feePct;

    @Column(name = "slippage_pct", precision = 12, scale = 6)
    private BigDecimal slippagePct;

    /**
     * Flat funding rate stub (basis points per 8h Binance funding period).
     * Applied per-position at close as
     * {@code notional × (rate / 10000) × (hold_hours / 8)}, signed by side.
     * Phase 0 stub for Phase 4 funding ingestion. Default {@code 0} keeps
     * legacy backtests bit-identical with their pre-V22 P&L.
     */
    @Column(name = "funding_rate_bps_per_8h", precision = 12, scale = 6)
    private BigDecimal fundingRateBpsPer8h;

    @Column(name = "min_notional", precision = 24, scale = 8)
    private BigDecimal minNotional;

    @Column(name = "min_qty", precision = 24, scale = 8)
    private BigDecimal minQty;

    @Column(name = "qty_step", precision = 24, scale = 8)
    private BigDecimal qtyStep;

    /**
     * Strategy permissions / constraints used during backtest.
     */
    @Column(name = "allow_long")
    private Boolean allowLong;

    @Column(name = "allow_short")
    private Boolean allowShort;

    @Column(name = "max_open_positions")
    private Integer maxOpenPositions;

    /**
     * Snapshot of runtime config at the time of backtest (wizard param overrides
     * as JSON). Later this can move to a proper JSONB column.
     *
     * <p><b>Do NOT re-add {@code @Lob}.</b> On Postgres, {@code @Lob String} maps
     * to an {@code oid} Large Object which requires an active JDBC transaction
     * to stream. Our Hikari pool runs with {@code auto-commit=true}, so reads
     * outside an explicit {@code @Transactional} throw
     * {@code "Large Objects may not be used in auto-commit mode"}. The
     * {@code columnDefinition = "TEXT"} below keeps the column as plain text;
     * Hibernate reads it as a normal {@link String}, no Lob streaming involved.
     */
    @Column(name = "config_snapshot", columnDefinition = "TEXT")
    private String configSnapshot;

    /**
     * Optional label/notes for manual experiments.
     */
    @Column(name = "run_label", length = 150)
    private String runLabel;

    @Column(name = "notes", length = 1000)
    private String notes;

    /**
     * Core summary metrics
     */
    @Column(name = "total_trades")
    private Integer totalTrades;

    @Column(name = "total_wins")
    private Integer totalWins;

    @Column(name = "total_losses")
    private Integer totalLosses;

    @Column(name = "win_rate", precision = 12, scale = 6)
    private BigDecimal winRate;

    @Column(name = "gross_profit", precision = 24, scale = 8)
    private BigDecimal grossProfit;

    @Column(name = "gross_loss", precision = 24, scale = 8)
    private BigDecimal grossLoss;

    @Column(name = "net_profit", precision = 24, scale = 8)
    private BigDecimal netProfit;

    @Column(name = "return_pct", precision = 12, scale = 6)
    private BigDecimal returnPct;

    /**
     * V60 — Mean per-trade return rate (pnl / notional × 100). Sizing-
     * independent companion to {@link #returnPct}; lets a strategy whose
     * sized notional is only a sliver of capital still show its per-trade
     * edge.
     */
    @Column(name = "avg_trade_return_pct", precision = 14, scale = 6)
    private BigDecimal avgTradeReturnPct;

    /**
     * V60 — Compounded return assuming 90% of equity sized per trade, in
     * percent. Walks trades chronologically; clamps to ruin (final
     * multiplier 0) if any step would zero equity. Order-sensitive.
     *
     * <p>Width NUMERIC(28,6) — 22 integer digits — defends against
     * compounding explosions on pathological / overfit sweep candidates
     * (a 1000-trade backtest with +5%/trade reaches a ~10^19 multiplier).
     */
    @Column(name = "geometric_return_pct_at_alloc_90", precision = 28, scale = 6)
    private BigDecimal geometricReturnPctAtAlloc90;

    @Column(name = "profit_factor", precision = 12, scale = 6)
    private BigDecimal profitFactor;

    @Column(name = "expectancy", precision = 24, scale = 8)
    private BigDecimal expectancy;

    @Column(name = "avg_win", precision = 24, scale = 8)
    private BigDecimal avgWin;

    @Column(name = "avg_loss", precision = 24, scale = 8)
    private BigDecimal avgLoss;

    @Column(name = "max_drawdown_pct", precision = 12, scale = 6)
    private BigDecimal maxDrawdownPct;

    @Column(name = "max_drawdown_amount", precision = 24, scale = 8)
    private BigDecimal maxDrawdownAmount;

    @Column(name = "ending_balance", precision = 24, scale = 8)
    private BigDecimal endingBalance;

    /**
     * Optional advanced metrics
     */
    @Column(name = "sharpe_ratio", precision = 12, scale = 6)
    private BigDecimal sharpeRatio;

    @Column(name = "sortino_ratio", precision = 12, scale = 6)
    private BigDecimal sortinoRatio;

    /**
     * Probabilistic Sharpe Ratio — P(true Sharpe > 0) given the observed
     * per-period Sharpe, sample size, skewness, and kurtosis. Value in
     * [0, 1]. Computed at run completion; null on PENDING/RUNNING/FAILED.
     */
    @Column(name = "psr", precision = 10, scale = 6)
    private BigDecimal psr;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    /**
     * Rough completion percent (0–100) while the run is executing. Written by
     * the coordinator loop through {@code BacktestProgressTracker}. Updated
     * throttled so a 10M-candle backtest doesn't bombard the DB — at most
     * ~100 writes per run. COMPLETED rows always land on 100; FAILED keeps
     * the last reported value so users can see where it crashed.
     */
    @Column(name = "progress_percent")
    private Integer progressPercent;

    /**
     * JSON payload written by the research analyzer when a run completes.
     * Contains headline metrics + feature-bucket diagnostics + MFE-capture
     * stats + best/worst trades. Stored as TEXT not {@code @Lob} for the same
     * reason as {@link #configSnapshot} — avoids the Postgres oid /
     * auto-commit trap.
     */
    @Column(name = "analysis_snapshot", columnDefinition = "TEXT")
    private String analysisSnapshot;

    /**
     * Reproducibility manifest — the application's git commit + display
     * version at submission time. Combined with {@link #configSnapshot},
     * {@link #asset}, {@link #interval}, {@link #startTime}, and
     * {@link #endTime}, these are sufficient to replay the run later.
     * Nullable for legacy rows.
     */
    @Column(name = "git_commit_sha", length = 40)
    private String gitCommitSha;

    @Column(name = "app_version", length = 50)
    private String appVersion;

    /**
     * Phase A — max concurrent open trades across all strategies in this
     * backtest. Null = no cap (legacy behaviour). Enforced by the backtest
     * executor before allowing a new entry.
     */
    @Column(name = "max_concurrent_strategies")
    private Integer maxConcurrentStrategies;

    /**
     * Phase A — per-strategy capital allocation override for this run only.
     * Key = strategy code (uppercase), value = allocation % (0–100).
     * Strategies missing from the map fall back to
     * {@code account_strategy.capital_allocation_pct}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_allocations", columnDefinition = "jsonb")
    private Map<String, BigDecimal> strategyAllocations;

    /**
     * V57 — per-strategy risk-pct override map. Key = uppercase strategy
     * code, value = fractional risk per trade (0 < x ≤ 0.20). Strategies
     * missing from the map fall back to
     * {@code account_strategy.risk_pct}; the fractional scale matches that
     * column (distinct from {@link #riskPerTradePct}, which is a percent
     * scalar kept for diagnostic snapshot only).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_risk_pcts", columnDefinition = "jsonb")
    private Map<String, BigDecimal> strategyRiskPcts;

    /**
     * V58 — per-strategy allowLong override map. Key = uppercase strategy
     * code, value = boolean. Wizard-supplied; lets operators flip a single
     * strategy's direction for one research run without changing the live
     * {@code account_strategy.allow_long}. Null map / missing key falls
     * back to the bound account_strategy's flag (V58 default).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_allow_long", columnDefinition = "jsonb")
    private Map<String, Boolean> strategyAllowLong;

    /**
     * V58 — per-strategy allowShort override map. Same semantics as
     * {@link #strategyAllowLong}. Null map / missing key falls back to the
     * bound account_strategy.allow_short.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_allow_short", columnDefinition = "jsonb")
    private Map<String, Boolean> strategyAllowShort;

    /**
     * Per-strategy interval map for multi-timeframe runs. Key = uppercase
     * strategy code, value = interval string (e.g. "15m"). When non-null,
     * the coordinator routes each strategy only to its own interval's bar
     * closes. Null = all strategies share the run's primary {@link #interval}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_intervals", columnDefinition = "jsonb")
    private Map<String, String> strategyIntervals;

    /**
     * Locked-holdout marker. {@code true} means this run was the one-shot
     * unbiased evaluation that ran AFTER a sweep picked its winner; the
     * sweep itself was never allowed to touch this window during
     * optimization. Set only by {@code ResearchSweepService.evaluateHoldout};
     * a unique partial index enforces at-most-one per sweep at the DB level.
     */
    @Column(name = "is_holdout_run", nullable = false)
    @Builder.Default
    private Boolean isHoldoutRun = Boolean.FALSE;

    /**
     * Origin tag — {@code USER} for runs submitted from the wizard,
     * {@code RESEARCHER} for runs submitted by the autonomous
     * research-orchestrator. Used by the UI to render a RESEARCHER badge so
     * users can tell their own work from agent-driven runs at a glance.
     * Defaulted in the DB so legacy rows and any caller that doesn't set
     * the field stay tagged USER.
     */
    @Column(name = "triggered_by", length = 20, nullable = false)
    @Builder.Default
    private String triggeredBy = "USER";

    /** When {@link #isHoldoutRun} is true, the sweep this holdout result
     *  belongs to. Null for non-holdout runs. */
    @Column(name = "holdout_for_sweep_id")
    private UUID holdoutForSweepId;

}