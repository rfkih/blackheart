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

    /** Non-null when something blew up. */
    private String errorMessage;
}
