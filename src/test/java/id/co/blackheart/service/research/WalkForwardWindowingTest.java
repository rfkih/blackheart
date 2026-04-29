package id.co.blackheart.service.research;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the K-fold window math. The original derivation
 * had {@code oosPerFold = available / K} which left {@code trainHead = 0}
 * for any exact division, and {@code validateSliceSizes} would reject
 * every realistic submission. This suite locks in the corrected math:
 * OOS fraction is the <i>total</i> coverage; train head gets the rest.
 */
class WalkForwardWindowingTest {

    private static final long DAY = Duration.ofDays(1).getSeconds();

    @Test
    void thirtyPctOosOver365DaysGivesUsableSlices() {
        // 365 available days, K=4, OOS=30% → train head ~256d, each OOS ~27d.
        long available = 365 * DAY;
        WalkForwardWindowing.SliceSizing s =
                WalkForwardWindowing.computeSliceSizing(available, 4, 0.30);

        long trainDays = s.trainHeadSeconds() / DAY;
        long oosDays = s.oosPerFoldSeconds() / DAY;
        assertTrue(trainDays >= 250 && trainDays <= 260, "trainHead ≈ 256d, got " + trainDays);
        assertTrue(oosDays >= 26 && oosDays <= 28, "oosPerFold ≈ 27d, got " + oosDays);
        // Sanity — passes the validation thresholds.
        assertDoesNotThrow(() -> WalkForwardWindowing.validateSliceSizes(s));
    }

    @Test
    void regressionExactDivisionDoesNotZeroOutTrainHead() {
        // The original buggy math: oosPerFold = available/K, trainHead = 0
        // for any exact division. With the fix, trainHead is the
        // (1 - oosFraction) chunk regardless of K.
        long available = 400 * DAY;     // exact 100d/K=4
        WalkForwardWindowing.SliceSizing s =
                WalkForwardWindowing.computeSliceSizing(available, 4, 0.30);

        assertTrue(s.trainHeadSeconds() > 0,
                "train head must NOT collapse to 0 for exact divisions");
        // train head = (1 - 0.30) × 400d = 280d
        assertEquals(280L, s.trainHeadSeconds() / DAY);
    }

    @Test
    void validateRejectsThinTrainSliceWithClearMessage() {
        // 60d available, K=4, OOS=70% → train head only 18d. Fails the
        // 30-day floor with a user-readable message.
        long available = 60 * DAY;
        WalkForwardWindowing.SliceSizing s =
                WalkForwardWindowing.computeSliceSizing(available, 4, 0.70);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> WalkForwardWindowing.validateSliceSizes(s));
        assertTrue(ex.getMessage().contains("train"),
                "error message should call out the train slice, got: " + ex.getMessage());
    }

    @Test
    void validateRejectsThinPerFoldOos() {
        // K=8, OOS=20%, available=180d → oosPerFold = 4.5d, below 7d floor.
        long available = 180 * DAY;
        WalkForwardWindowing.SliceSizing s =
                WalkForwardWindowing.computeSliceSizing(available, 8, 0.20);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> WalkForwardWindowing.validateSliceSizes(s));
        assertTrue(ex.getMessage().contains("OOS") || ex.getMessage().contains("Per-fold"),
                "error should call out the OOS fold, got: " + ex.getMessage());
    }

    @Test
    void buildFoldsProducesNonOverlappingOosTilesContiguousAcrossSweep() {
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(365);
        List<WalkForwardWindowing.Fold> folds =
                WalkForwardWindowing.buildFolds(start, end, 4, 0.30);

        assertEquals(4, folds.size());

        // Fold-1 train starts at sweepStart; fold-1 OOS starts after train head.
        assertEquals(start, folds.get(0).trainFromDate());

        // Each fold's train ends exactly where its OOS begins.
        for (WalkForwardWindowing.Fold f : folds) {
            assertEquals(f.oosFromDate(), f.trainToDate(),
                    "fold " + f.foldIndex() + " train/OOS not contiguous");
        }

        // OOS slices are contiguous (no gaps, no overlap).
        for (int i = 1; i < folds.size(); i++) {
            assertEquals(folds.get(i - 1).oosToDate(), folds.get(i).oosFromDate(),
                    "OOS slices " + (i) + "→" + (i + 1) + " must be contiguous");
        }

        // Last fold's OOS ends at sweepEnd (absorbs any rounding remainder).
        assertEquals(end, folds.get(folds.size() - 1).oosToDate());
    }

    @Test
    void rejectsOosFractionOutsideOpenZeroOne() {
        long available = 365 * DAY;
        assertThrows(IllegalArgumentException.class,
                () -> WalkForwardWindowing.computeSliceSizing(available, 4, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> WalkForwardWindowing.computeSliceSizing(available, 4, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> WalkForwardWindowing.computeSliceSizing(available, 4, -0.1));
    }
}
