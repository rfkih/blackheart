package id.co.blackheart.model;

/**
 * Discriminator for {@link HistoricalBackfillJob}. Each value matches a
 * registered {@code HistoricalJobHandler} bean — adding a new job kind is
 * a two-step change: append to this enum + register a handler.
 *
 * <p>Persisted as VARCHAR via {@code @Enumerated(STRING)} to match the
 * {@code job_type} column. Renaming an enum constant requires a Flyway
 * data migration.
 */
public enum JobType {
    /**
     * Composite repair: backfill missing market_data candles, then missing
     * feature_store rows, then optionally patch selected NULL columns. The
     * unified UI's "Run repair" button submits this with the operator's
     * selections in {@code params}.
     */
    COVERAGE_REPAIR,

    /**
     * Patch a single NULL column for an indicator. Auto-discovers affected
     * (symbol, interval) pairs when not specified. Generalized successor to
     * the hand-coded {@code backfillSlope200} pattern.
     */
    PATCH_NULL_COLUMN,

    /**
     * Delete-then-insert all feature_store rows in a date range. Use after
     * indicator code or parameters change and existing rows are stale.
     * Destructive; gated behind a UI confirmation.
     */
    RECOMPUTE_RANGE,

    /**
     * Pull Binance fapi funding-rate history into {@code funding_rate_history}.
     * Idempotent on (symbol, funding_time). Run once per perp before the
     * funding-column patches.
     */
    BACKFILL_FUNDING_HISTORY
}
