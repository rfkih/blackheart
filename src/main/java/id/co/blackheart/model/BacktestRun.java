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
     * Stable strategy identifier used by StrategyExecutorFactory.
     * Example: TREND_PULLBACK_SINGLE_EXIT, RAHT_V1
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

}