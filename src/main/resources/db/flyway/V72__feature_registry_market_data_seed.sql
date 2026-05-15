-- V72 — Grant blackheart_trading SELECT on market_data + seed the first
-- market_data-sourced feature in feature_registry.
--
-- Why:
--   M3 step 2 brings in features computed from OHLCV bars instead of
--   publisher-event raw rows. The compute worker runs as
--   blackheart_trading; without SELECT on market_data it can't read price
--   history. Granting here keeps the role separation otherwise intact
--   (still no INSERT/UPDATE — write side stays with the trading JVM).
--
--   Seeded feature `btc_realized_vol_30d` v1: 30-day annualized realized
--   volatility on BTCUSDT 1h bars (window=720, annualize=8760). Matches
--   blueprint § 5.4 entry that says "Already computed" in feature_store —
--   we recompute it here in the new pipeline so all M3 features live
--   under the same registry/values pattern.
--
--   Idempotent. The GRANT is a no-op if already present, the INSERT uses
--   the same UPSERT pattern as V70/V71.
--
-- PIT anchor convention for market_data features (READ BEFORE WRITING
-- A LABEL TRANSFORMER):
--   feature_values.ts is the bar's start_time. close_price for a bar
--   starting at T is only finalized at T + interval. Backward-looking
--   features (this one included) are safe as long as the consumer joins
--   with `ts <= bar.start_time - interval` so it reads the previous
--   bar's feature. Forward-looking labels MUST NOT treat the anchor
--   bar's close as "current" — they need to anchor differently or
--   shift by one bar. See src/blackheart_ingest/features/compute.py
--   _MARKET_DATA_COLS comment block.
--
-- inputs JSONB shape (consistent with V70/V71):
--   {
--     "series_ids": [...],       -- macro_raw: series_id list; market_data: column list
--     "args": {...},             -- kwargs reconstructing the transformer
--     "raw_table": "market_data" -- discriminator; present iff raw_table != macro_raw
--   }

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_trading') THEN
        EXECUTE 'GRANT SELECT ON market_data TO blackheart_trading';
    END IF;
END $$;

INSERT INTO feature_registry (
    feature_name, version, family, owner, transformer_ref, inputs,
    output_dtype, symbols, intervals,
    pit_safe, ffill_policy, max_ffill_age_hours, backfill_strategy,
    label_for_model, label_direction, status, created_by, updated_by
) VALUES
    (
        'btc_realized_vol_30d', 1, 'market_structure', 'blueprint',
        'blackheart_ingest.features.definitions:_rolling_realized_vol',
        '{"series_ids":["close_price"],"args":{"close_col":"close_price","window_bars":720,"min_periods":240,"annualize_factor":8760},"raw_table":"market_data"}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], ARRAY['1h'],
        TRUE, NULL, NULL, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V72 migration', 'V72 migration'
    )
ON CONFLICT (feature_name, version) DO UPDATE SET
    family = EXCLUDED.family,
    owner = EXCLUDED.owner,
    transformer_ref = EXCLUDED.transformer_ref,
    inputs = EXCLUDED.inputs,
    output_dtype = EXCLUDED.output_dtype,
    symbols = EXCLUDED.symbols,
    intervals = EXCLUDED.intervals,
    pit_safe = EXCLUDED.pit_safe,
    ffill_policy = EXCLUDED.ffill_policy,
    max_ffill_age_hours = EXCLUDED.max_ffill_age_hours,
    backfill_strategy = EXCLUDED.backfill_strategy,
    status = EXCLUDED.status,
    updated_time = NOW(),
    updated_by = EXCLUDED.updated_by;
