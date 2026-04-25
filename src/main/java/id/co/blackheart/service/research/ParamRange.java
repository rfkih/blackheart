package id.co.blackheart.service.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Half-open numeric range with an explicit step. The research-mode sweep uses
 * {@link #expand()} to enumerate candidate values in round 1 and
 * {@link #refineAround(BigDecimal, int)} to tighten around an elite value in
 * subsequent rounds.
 *
 * <p>The {@code [min, max]} interval is inclusive on both ends — if the step
 * doesn't land on {@code max} exactly, {@code max} is still included so the
 * user's stated upper bound is honoured.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParamRange {

    private BigDecimal min;
    private BigDecimal max;
    private BigDecimal step;

    /** All values in the range, inclusive of both endpoints. */
    public java.util.List<BigDecimal> expand() {
        if (min == null || max == null || step == null || step.signum() <= 0) {
            return java.util.List.of();
        }
        if (min.compareTo(max) > 0) return java.util.List.of();

        java.util.List<BigDecimal> out = new java.util.ArrayList<>();
        BigDecimal cur = min;
        int guard = 0; // belt-and-braces against infinite loops from a tiny step.
        while (cur.compareTo(max) <= 0 && guard++ < 10_000) {
            out.add(cur);
            cur = cur.add(step);
        }
        // Include max exactly when the last grid point doesn't land on it.
        if (!out.isEmpty() && out.get(out.size() - 1).compareTo(max) < 0) {
            out.add(max);
        }
        return out;
    }

    /**
     * Round-N refinement: {@code half} steps below and above the seed,
     * clamped to {@code [min, max]}. With {@code half = 1} you get the seed +
     * its two immediate neighbours (up to 3 points).
     */
    public java.util.List<BigDecimal> refineAround(BigDecimal seed, int half) {
        if (seed == null || step == null || step.signum() <= 0) return java.util.List.of();
        java.util.Set<BigDecimal> out = new java.util.LinkedHashSet<>();
        for (int i = -half; i <= half; i++) {
            BigDecimal candidate = seed.add(step.multiply(BigDecimal.valueOf(i)));
            if (min != null && candidate.compareTo(min) < 0) continue;
            if (max != null && candidate.compareTo(max) > 0) continue;
            out.add(candidate);
        }
        return new java.util.ArrayList<>(out);
    }
}
