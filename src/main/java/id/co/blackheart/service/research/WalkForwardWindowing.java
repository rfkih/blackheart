package id.co.blackheart.service.research;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function window math for K-fold walk-forward sweeps.
 *
 * <p>The walk-forward layout is anchored — train always starts at
 * {@code fromDate} and expands to include each prior fold's data. OOS
 * slices are non-overlapping chronological tiles after a single
 * {@code trainHead} warmup. Holdout (when reserved) is the tail and is
 * excluded from {@code availableSeconds}.
 *
 * <pre>
 *   [== train head ==][O1][O2][O3][O4]   -- (holdout reserved by caller)
 *   fold 1 train: [from .. O1.start)   OOS: O1
 *   fold 2 train: [from .. O2.start)   OOS: O2
 *   fold 3 train: [from .. O3.start)   OOS: O3
 *   fold 4 train: [from .. O4.start)   OOS: O4
 * </pre>
 *
 * <p>The math originally had {@code oosPerFold = available / K} which
 * left {@code trainHead} at the integer-division remainder (effectively
 * zero), and {@link #validateSliceSizes} would reject every realistic
 * submission at the train-head ≥ 30-day floor. The corrected derivation
 * uses {@code oosFractionPct} as the <i>total</i> OOS coverage and
 * derives {@code trainHead} from the rest of the available window.
 */
public final class WalkForwardWindowing {

    private WalkForwardWindowing() {}

    /** Per-fold slice sizes in seconds. {@code oosPerFold × K} ≤ total OOS
     *  coverage; the last fold absorbs any rounding remainder. */
    public record SliceSizing(long trainHeadSeconds, long oosPerFoldSeconds) {}

    /** One fold's date range — train is {@code [fromDate, oosFrom)}. */
    public record Fold(
            int foldIndex,
            LocalDateTime trainFromDate,
            LocalDateTime trainToDate,
            LocalDateTime oosFromDate,
            LocalDateTime oosToDate
    ) {}

    /**
     * Compute slice sizes from the available window, fold count, and the
     * OOS coverage as a fraction of available seconds.
     *
     * @param availableSeconds  total length of the non-holdout window
     * @param k                 number of folds (validated upstream to be ≥ 2)
     * @param oosFraction       OOS coverage as a fraction in (0, 1)
     */
    public static SliceSizing computeSliceSizing(long availableSeconds, int k, double oosFraction) {
        if (availableSeconds <= 0 || k <= 0) return new SliceSizing(0, 0);
        if (oosFraction <= 0 || oosFraction >= 1) {
            throw new IllegalArgumentException("oosFraction must be in (0, 1), got " + oosFraction);
        }
        long oosTotalSeconds = (long) (availableSeconds * oosFraction);
        long oosPerFold = oosTotalSeconds / k;
        long trainHead = availableSeconds - oosTotalSeconds;
        return new SliceSizing(trainHead, oosPerFold);
    }

    /**
     * Materialise the K folds against concrete clock times.
     *
     * @param sweepStart  start of the available window (= spec.fromDate)
     * @param sweepEnd    end of the available window (= holdoutFromDate when
     *                    holdout is reserved, else spec.toDate)
     */
    public static List<Fold> buildFolds(LocalDateTime sweepStart,
                                        LocalDateTime sweepEnd,
                                        int k,
                                        double oosFraction) {
        long availableSeconds = java.time.Duration.between(sweepStart, sweepEnd).getSeconds();
        SliceSizing sizing = computeSliceSizing(availableSeconds, k, oosFraction);

        List<Fold> folds = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            LocalDateTime oosFrom = sweepStart.plusSeconds(
                    sizing.trainHeadSeconds() + i * sizing.oosPerFoldSeconds());
            // Last fold absorbs any rounding remainder so the OOS coverage
            // hits sweepEnd exactly. Earlier folds are equal-sized tiles.
            LocalDateTime oosTo = (i == k - 1)
                    ? sweepEnd
                    : oosFrom.plusSeconds(sizing.oosPerFoldSeconds());
            folds.add(new Fold(i + 1, sweepStart, oosFrom, oosFrom, oosTo));
        }
        return folds;
    }

    /**
     * Validate slice sizes meet minimums. Throws {@link IllegalArgumentException}
     * with a user-readable message on failure. Bounds:
     * <ul>
     *   <li>train head ≥ 30 days — the optimization needs something to fit on</li>
     *   <li>OOS per fold ≥ 7 days — each fold needs enough bars to score</li>
     * </ul>
     */
    public static void validateSliceSizes(SliceSizing sizing) {
        long minTrainSeconds = java.time.Duration.ofDays(30).getSeconds();
        long minOosSeconds = java.time.Duration.ofDays(7).getSeconds();

        if (sizing.trainHeadSeconds() < minTrainSeconds) {
            throw new IllegalArgumentException(
                    "Fold-1 train slice would be " + (sizing.trainHeadSeconds() / 86400) + " days. "
                            + "Need >= 30 — widen the date range, lower K, or shrink holdout.");
        }
        if (sizing.oosPerFoldSeconds() < minOosSeconds) {
            throw new IllegalArgumentException(
                    "Per-fold OOS would be " + (sizing.oosPerFoldSeconds() / 86400) + " days. "
                            + "Lower walkForwardWindows or widen the date range.");
        }
    }
}
