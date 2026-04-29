package id.co.blackheart.service.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** One row in a sweep's leaderboard: one param combo → one backtest → one set of metrics. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SweepResult {

    /** Which research round produced this combo. {@code 1} for flat sweeps or
     *  round 1 of a research session; {@code 2+} for refinement rounds. */
    private Integer round;

    /** The varied params' concrete values for this combo. */
    private Map<String, Object> paramSet;

    /** Backtest run that executed this combo. */
    private UUID backtestRunId;

    /** {@code PENDING}, {@code RUNNING}, {@code COMPLETED}, {@code FAILED}. */
    private String status;

    /** Wall-clock ms from RUNNING → COMPLETED/FAILED. {@code null} until the
     *  combo finishes. Frontend uses this to render an in-flight elapsed
     *  counter against the running combo. */
    private Long elapsedMs;

    /** Most recently observed BacktestRun.progress_percent (0..100) for the
     *  combo's underlying backtest. Mirrors the value the per-run page shows.
     *  Null until the sweep thread polls the run for the first time; flips to
     *  100 on COMPLETED, retains the last reported value on FAILED. */
    private Integer progressPercent;

    /** Populated once the run completes + analyzer fires. */
    private Integer tradeCount;
    private BigDecimal winRate;
    private BigDecimal profitFactor;
    private BigDecimal avgR;
    private BigDecimal netPnl;
    private BigDecimal maxDrawdown;
    private Integer maxConsecutiveLosses;
    /**
     * Annualized Sharpe (× √252) — same units as on the backtest result page.
     * In TRAIN_OOS split mode this mirrors {@link #oosSharpeRatio}, so existing
     * leaderboard sort/filter/rank machinery automatically operates on OOS.
     */
    private BigDecimal sharpeRatio;
    /** Probabilistic Sharpe Ratio in [0, 1]. Mirrors OOS PSR in split mode. */
    private BigDecimal psr;

    // ── Train/OOS split (only populated when SweepSpec.splitMode == TRAIN_OOS) ──

    /** Backtest run that ran over the train slice of the window. */
    private UUID trainBacktestRunId;
    private Integer trainTradeCount;
    private BigDecimal trainSharpeRatio;
    private BigDecimal trainPsr;
    private BigDecimal trainNetPnl;

    /** Backtest run that ran over the OOS slice — the one that actually
     *  signals real edge after train-time optimization. */
    private UUID oosBacktestRunId;
    private Integer oosTradeCount;
    private BigDecimal oosSharpeRatio;
    private BigDecimal oosPsr;
    private BigDecimal oosNetPnl;

    // ── K-fold walk-forward (only populated when splitMode == WALK_FORWARD_K) ──

    /** Per-fold metrics, ordered by foldIndex. K entries when status is
     *  COMPLETED; fewer if some folds failed (status will be FAILED). */
    @lombok.Builder.Default
    private java.util.List<WindowResult> windowResults = new java.util.ArrayList<>();

    /** Mean of the per-fold OOS Sharpes — the headline ranking metric in
     *  walk-forward mode. Mirrored to {@link #sharpeRatio} so existing
     *  leaderboard sort, DSR threshold, and frontend display work without
     *  any K-fold-specific plumbing downstream. */
    private BigDecimal meanOosSharpe;
    /** Stddev across the per-fold OOS Sharpes — the regime-sensitivity
     *  signal. Low stddev relative to mean = robust strategy; high stddev
     *  = strategy depends on a regime that some folds don't cover. */
    private BigDecimal stddevOosSharpe;

    /** Non-null when something blew up. */
    private String errorMessage;
}
