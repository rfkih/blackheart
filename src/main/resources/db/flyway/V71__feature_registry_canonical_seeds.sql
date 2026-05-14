-- V71 — Seed feature_registry with the 14 canonical M3 features from the
-- blueprint (§ 5.1–5.5) that can be computed from data we have on hand.
--
-- Why:
--   V70 seeded 3 starter features (vix_close, dxy_close, dxy_zscore_30d)
--   for compute-pipeline validation. This migration adds the canonical
--   features the trading + research layers will actually consume in M4+:
--     §5.1 Macro regime    — 6 features
--     §5.2 Positioning     — 5 features
--     §5.3 Flows           — 2 features (the 2 exchange-netflow features
--                            need CoinMetrics paid tier; deferred)
--     §5.4 Market structure — 2 features (btc_perp_basis needs spot price
--                            data we don't ingest yet; btc_realized_vol_30d
--                            lives in feature_store from market_data)
--     §5.5 Events          — 1 feature (FOMC calendar features need
--                            historical ForexFactory data; MVP is
--                            current-week only)
--
--   Total: 14 here + 3 starters from V70 = 17 in registry.
--   Still pending (need data plumbing): 4 features + 4 labels.
--
-- transformer_ref convention: "module:callable" — the factory in
-- blackheart_ingest.features.definitions. Args live in inputs.args so
-- the registry row alone is enough to reconstruct the transformer.
--
-- ON CONFLICT DO UPDATE makes re-runs idempotent AND lets later seed
-- corrections (e.g. tuning a window) propagate to the live registry
-- without manual UPDATEs.

INSERT INTO feature_registry (
    feature_name, version, family, owner, transformer_ref, inputs,
    output_dtype, symbols, intervals,
    pit_safe, ffill_policy, max_ffill_age_hours, backfill_strategy,
    label_for_model, label_direction, status, created_by, updated_by
) VALUES
    -- ── § 5.1 Macro regime ──────────────────────────────────────────
    (
        'real_yield_10y_level', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_passthrough',
        '{"series_ids":["DFII10"],"args":{"col":"DFII10"}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'real_yield_10y_change_20d', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_change_diff',
        '{"series_ids":["DFII10"],"args":{"col":"DFII10","periods":20}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'dxy_zscore_252d', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_rolling_zscore',
        '{"series_ids":["DTWEXBGS"],"args":{"col":"DTWEXBGS","window":252,"min_periods":60}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'dxy_momentum_20d', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_change_pct',
        '{"series_ids":["DTWEXBGS"],"args":{"col":"DTWEXBGS","periods":20}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'vix_percentile_252d', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_rolling_percentile_rank',
        '{"series_ids":["VIXCLS"],"args":{"col":"VIXCLS","window":252,"min_periods":60}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'term_spread_2s10s', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_passthrough',
        '{"series_ids":["T10Y2Y"],"args":{"col":"T10Y2Y"}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    -- ── § 5.2 Positioning ───────────────────────────────────────────
    (
        'btc_funding_8h', 1, 'positioning', 'blueprint',
        'blackheart_ingest.features.definitions:_passthrough',
        '{"series_ids":["binance_funding_rate_btcusdt"],"args":{"col":"binance_funding_rate_btcusdt"}}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], NULL,
        TRUE, 'last_value', 24, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'btc_funding_zscore_30d', 1, 'positioning', 'blueprint',
        'blackheart_ingest.features.definitions:_rolling_zscore',
        '{"series_ids":["binance_funding_rate_btcusdt"],"args":{"col":"binance_funding_rate_btcusdt","window":90,"min_periods":20}}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], NULL,
        TRUE, 'last_value', 24, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'btc_oi_change_24h_pct', 1, 'positioning', 'blueprint',
        'blackheart_ingest.features.definitions:_change_pct',
        '{"series_ids":["binance_open_interest_btcusdt_1h"],"args":{"col":"binance_open_interest_btcusdt_1h","periods":24}}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], ARRAY['1h'],
        TRUE, 'last_value', 24, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'taker_buy_ratio_4h', 1, 'positioning', 'blueprint',
        'blackheart_ingest.features.definitions:_passthrough',
        '{"series_ids":["binance_taker_buy_sell_ratio_btcusdt_4h"],"args":{"col":"binance_taker_buy_sell_ratio_btcusdt_4h"}}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], ARRAY['4h'],
        TRUE, 'last_value', 24, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'topls_ratio_change_24h', 1, 'positioning', 'blueprint',
        'blackheart_ingest.features.definitions:_change_diff',
        '{"series_ids":["binance_long_short_ratio_btcusdt_1h"],"args":{"col":"binance_long_short_ratio_btcusdt_1h","periods":24}}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], ARRAY['1h'],
        TRUE, 'last_value', 24, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    -- ── § 5.3 Flows (2 of 4) ────────────────────────────────────────
    (
        'stablecoin_supply_change_7d', 1, 'flow', 'blueprint',
        'blackheart_ingest.features.definitions:_sum_then_change_pct',
        '{"series_ids":["stablecoin_usdt_circulating_usd","stablecoin_usdc_circulating_usd"],"args":{"cols":["stablecoin_usdt_circulating_usd","stablecoin_usdc_circulating_usd"],"periods":7}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'stablecoin_supply_change_30d', 1, 'flow', 'blueprint',
        'blackheart_ingest.features.definitions:_sum_then_change_pct',
        '{"series_ids":["stablecoin_usdt_circulating_usd","stablecoin_usdc_circulating_usd"],"args":{"cols":["stablecoin_usdt_circulating_usd","stablecoin_usdc_circulating_usd"],"periods":30}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    -- ── § 5.4 Market structure (2 of 4) ─────────────────────────────
    (
        'btc_dominance_change_7d', 1, 'market_structure', 'blueprint',
        'blackheart_ingest.features.definitions:_change_diff',
        '{"series_ids":["btc_dominance_pct"],"args":{"col":"btc_dominance_pct","periods":7}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 24, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    (
        'eth_btc_ratio_momentum_20d', 1, 'market_structure', 'blueprint',
        'blackheart_ingest.features.definitions:_ratio_momentum',
        '{"series_ids":["ethereum_price_usd","bitcoin_price_usd"],"args":{"num_col":"ethereum_price_usd","den_col":"bitcoin_price_usd","periods":20}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 24, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
    ),
    -- ── § 5.5 Events (1 of 3) ───────────────────────────────────────
    (
        'fear_greed_value', 1, 'sentiment', 'blueprint',
        'blackheart_ingest.features.definitions:_passthrough',
        '{"series_ids":["fear_and_greed"],"args":{"col":"fear_and_greed"}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 48, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V71 migration', 'V71 migration'
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
