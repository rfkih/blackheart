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
    BACKFILL_FUNDING_HISTORY,

    // ── ML/sentiment ingestion (V67, Phase 1 / M1+) ─────────────────────────
    // Each delegates to the Python ingest service via HTTP. Handler is a thin
    // adapter that maps params + tracks progress. Until the Python service
    // exists (Phase 1 M2), handlers are stubs that simulate progress.

    /**
     * FRED + ALFRED macro series → {@code macro_raw}. Params: {@code series_ids},
     * {@code start}, {@code end}, {@code use_alfred_vintage}. ALFRED used for
     * revision-prone series (CPI, M2, employment).
     */
    BACKFILL_ML_FRED,

    /**
     * Binance public macro feeds (funding rate, OI, top L/S, taker volumes)
     * → {@code macro_raw}. Distinct from {@link #BACKFILL_FUNDING_HISTORY}
     * which writes {@code funding_rate_history} for feature_store funding cols.
     */
    BACKFILL_ML_BINANCE_MACRO,

    /**
     * DefiLlama stablecoin supply + chain TVL → {@code onchain_raw}.
     */
    BACKFILL_ML_DEFILLAMA,

    /**
     * CoinMetrics community-tier on-chain metrics (exchange netflow, active
     * addresses, realized cap) → {@code onchain_raw}.
     */
    BACKFILL_ML_COINMETRICS,

    /**
     * CoinGecko free-tier global metrics (BTC dominance, total market cap)
     * + per-coin price/volume → {@code macro_raw}.
     */
    BACKFILL_ML_COINGECKO,

    /**
     * alternative.me Fear & Greed Index history → {@code macro_raw}. Simplest
     * source — single endpoint, no auth. Implemented first as contract
     * validator.
     */
    BACKFILL_ML_ALTERNATIVE_ME,

    /**
     * ForexFactory economic calendar scrape (FOMC, CPI, NFP, Unemployment)
     * → {@code macro_raw}. Most fragile source — HTML structure changes
     * periodically; wrap in robust retry + parse-error tolerance.
     */
    BACKFILL_ML_FOREXFACTORY
}
