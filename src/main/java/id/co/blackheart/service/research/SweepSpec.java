package id.co.blackheart.service.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Input payload for {@code POST /api/v1/research/sweeps}. Describes a grid of
 * parameter values to explore against a fixed backtest window.
 *
 * <p>{@link #paramGrid} is "varied parameter name" → "list of values to try".
 * The sweep runner expands the cross-product of every key's values. A 3×3
 * grid = 9 backtests; hard-capped server-side to prevent accidental
 * 10×10×10 submissions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SweepSpec {

    /** Strategy to sweep. Currently only {@code "TPR"} is supported. */
    private String strategyCode;

    /** Account strategy row to use for the backtest's accountStrategyId. Any
     *  valid AS id will do — TPR reads per-run overrides from the context,
     *  not from the AS row. */
    private UUID accountStrategyId;

    private String asset;
    private String interval;

    private LocalDateTime fromDate;
    private LocalDateTime toDate;

    private BigDecimal initialCapital;

    /** Optional label for humans. */
    private String label;

    /** Cross-product grid of explicit values. Used when {@link #rounds} is 1
     *  (or null). Mutually-exclusive with {@link #paramRanges} but we don't
     *  reject presence of both — if both are set we take {@code paramRanges}
     *  because it carries enough info to drive refinement. */
    private Map<String, List<Object>> paramGrid;

    /**
     * Research-mode: {key → [min, max, step]} ranges. When {@link #rounds} is
     * {@code > 1} the orchestrator expands this into the round-1 grid, then
     * refines around each round's elites for subsequent rounds. Required when
     * {@code rounds > 1}.
     */
    private Map<String, ParamRange> paramRanges;

    /**
     * Number of iterative-refinement rounds. Defaults to {@code 1} — a
     * single-round sweep is the flat grid we started with. Capped server-side
     * to avoid runaway sessions.
     */
    private Integer rounds;

    /**
     * Fraction of each round's results kept as "elites" whose neighborhoods
     * seed the next round. Defaults to {@code 0.25}. Higher keeps more
     * candidates (more exploration); lower narrows faster.
     */
    private BigDecimal elitePct;

    /**
     * Which headline metric to rank the leaderboard by.
     * One of: {@code avgR}, {@code profitFactor}, {@code netPnl}, {@code winRate}.
     * Default is {@code avgR}.
     */
    private String rankMetric;

    /**
     * Pinned parameter overrides — values held constant across every combo,
     * unlike {@link #paramGrid} / {@link #paramRanges} whose keys are swept.
     * Useful for moving a param off its default for the whole sweep without
     * burning a sweep dimension on it.
     *
     * <p>Keys must not overlap with {@code paramGrid} / {@code paramRanges} —
     * a key that's both swept and pinned is ambiguous. The runner merges
     * fixed values as the base layer, then applies the per-combo swept values
     * on top, so a swept key would always win anyway, but we reject the
     * collision at submit time to surface the user's mistake.
     *
     * <p>Refinement (research mode) only walks the swept dimensions — fixed
     * values stay locked across all rounds.
     */
    private Map<String, Object> fixedParams;
}
