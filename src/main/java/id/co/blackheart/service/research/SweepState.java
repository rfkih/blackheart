package id.co.blackheart.service.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


/** Top-level sweep record — returned from the create + detail endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SweepState {

    private UUID sweepId;
    private UUID userId;
    private SweepSpec spec;

    /** {@code PENDING}, {@code RUNNING}, {@code COMPLETED}, {@code FAILED}, {@code CANCELLED}. */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /** Total combos expanded across every round so far plus the current round's
     *  queued combos. Grows over time in research-mode sweeps as new rounds
     *  are planned. */
    private int totalCombos;
    /** Combos whose backtest has finished (either COMPLETED or FAILED). */
    private int finishedCombos;

    /** Research-mode only: which round is currently running (1-based). */
    private Integer currentRound;
    /** Research-mode only: total rounds planned. {@code null} on flat sweeps. */
    private Integer totalRounds;

    /** Number of round-2+ combos that hit the per-round cap and were dropped.
     *  Surfaced so the UI can warn the user the search was wider than what ran. */
    private Integer roundsTruncated;

    /** Ordered by {@code createdAt} of the underlying backtest — not by rank.
     *  Leaderboard ranking is done client-side (or by the controller on demand). */
    private List<SweepResult> results;

    /**
     * Cohort-level Deflated-Sharpe context, computed on read from the
     * combo Sharpes. {@code dsrThresholdSharpe} is the expected maximum
     * Sharpe under N independent null trials (true SR = 0). Top combos
     * exceeding this threshold are evidence beyond multiple-comparison
     * luck; combos at or below it are statistically indistinguishable
     * from picking the best of N coin flips. Null on sweeps with fewer
     * than two completed combos. Both values are annualized for display.
     */
    private BigDecimal dsrThresholdSharpe;
    /** Stddev of the cohort's annualized Sharpes — diagnostic for the
     *  DSR threshold calculation. */
    private BigDecimal dsrCohortStddev;

    /** When {@link SweepSpec#getHoldoutFractionPct()} is set, the start of
     *  the locked holdout slice (= effective sweep window end). Surfaced so
     *  the UI can render "Sweep optimized over X→Y, holdout reserved for Y→Z". */
    private LocalDateTime holdoutFromDate;
    private LocalDateTime holdoutToDate;

    /** {@link id.co.blackheart.model.BacktestRun#getBacktestRunId()} of the
     *  one-shot holdout evaluation, set after the user calls
     *  {@code POST /sweeps/:id/evaluate-holdout}. Null until then. The DB
     *  enforces at-most-one via a unique partial index. */
    private UUID holdoutBacktestRunId;
}
