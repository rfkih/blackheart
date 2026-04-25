package id.co.blackheart.service.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
