package id.co.blackheart.service.technicalindicator.patcher;

import id.co.blackheart.model.FeatureStore;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Strategy pattern for patching one (or a small bundle of) feature_store
 * column(s) on rows that have them NULL. Each Spring bean implementing this
 * abstract class is auto-registered by {@link FeaturePatcherRegistry} and
 * dispatched on by {@link FeaturePatcherService}.
 *
 * <p>Adding a new indicator becomes a closed change:
 * <ol>
 *   <li>Add the column to {@code feature_store} via a Flyway migration.</li>
 *   <li>Add the field to {@link FeatureStore}.</li>
 *   <li>Drop a {@code @Component class FooPatcher extends FeaturePatcher<FooAux>}.</li>
 * </ol>
 * The unified UI then auto-lists "Patch NULL foo" as a repair action.
 *
 * <p>Patchers own their own data loading. The framework loops month-sized
 * windows, finds rows with NULL in {@link #primaryColumn()}, calls
 * {@link #buildAux(String, String, LocalDateTime, LocalDateTime)} once per
 * window, then walks {@link #patchRow(FeatureStore, Object)} for each NULL
 * row. Saves are chunked.
 *
 * @param <T> per-window auxiliary type — implementation detail of the
 *            patcher (e.g. a precomputed TA4j indicator + index map).
 */
public abstract class FeaturePatcher<T> {

    /**
     * The column the framework uses to discover rows needing this patch.
     * The patcher may write more columns than just this one (e.g. the
     * funding patcher writes three from one FundingRate series); discovery
     * still keys on a single column.
     */
    public abstract String primaryColumn();

    /**
     * All feature_store columns this patcher writes. Defaults to
     * {@code List.of(primaryColumn())} — override when the patcher writes
     * multiple columns from shared auxiliary data.
     *
     * <p>Used by the UI to show "this patch will write columns X, Y, Z" so
     * the operator understands the blast radius.
     */
    public List<String> writtenColumns() {
        return List.of(primaryColumn());
    }

    /**
     * Build per-window auxiliary state. Called once per month-sized window
     * before iterating rows. The patcher may extend the time range with its
     * own warmup (e.g. EMA200 needs ~200 bars before windowStart) — the
     * framework does not preload market_data on the patcher's behalf.
     *
     * <p>Return {@code null} to skip the window (e.g. cold-start: no source
     * data available yet). The framework treats null as "0 rows updated"
     * and moves on.
     */
    public abstract T buildAux(String symbol, String interval,
                               LocalDateTime windowStart, LocalDateTime windowEnd);

    /**
     * Mutate {@code row} in-place using {@code aux} and report whether the
     * row was actually filled. May write multiple columns. The framework
     * handles persistence — do not save inside this method.
     *
     * <p>Return {@link PatchOutcome#FILLED} when the row's columns were set
     * to non-null values. Return {@link PatchOutcome#NOT_FILLED} when the
     * patcher had insufficient source data and left the row untouched (the
     * row will remain NULL in the DB). The framework uses the outcome to
     * decide whether to flush the row and to keep accurate metrics — a
     * NOT_FILLED row is not counted as "patched".
     *
     * <p>Implementations must NOT set columns to {@code null} and return
     * {@code FILLED}; that defeats the metrics. If the source data is
     * insufficient, return {@code NOT_FILLED} without mutating the row.
     */
    public abstract PatchOutcome patchRow(FeatureStore row, T aux);

    /** Result of a single {@link #patchRow} call. */
    public enum PatchOutcome {
        /** Row's columns were set to non-null values. */
        FILLED,
        /** Patcher had insufficient source data; row was left unchanged. */
        NOT_FILLED
    }
}
