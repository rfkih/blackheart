-- V73 — Seed feature_registry with 3 of the 4 forward-looking labels
-- from blueprint § 5.6. label_triple_barrier is deferred to a follow-up
-- migration because it needs the bar-stepping algorithm + careful PIT
-- treatment for the intra-bar fill rule.
--
-- Why:
--   Labels are forward-looking features (``label_direction='forward'``).
--   They're registered in the same feature_registry/feature_values
--   tables as input features but flagged so:
--     1. Downstream PIT checks know to invert the direction.
--     2. Training pipelines distinguish input features from targets.
--
--   The compute engine treats labels as transformers that just happen to
--   call ``c.shift(-N)``; persistence is identical. The trailing rows
--   (last horizon bars of the input window) emit NaN because future
--   data doesn't exist yet — engine's .dropna() filters them out.
--
-- Inputs JSONB shape (consistent with V70/V71/V72):
--   {"series_ids":[...],"args":{...},"raw_table":"market_data"}
--
-- Label_for_model mapping (per blueprint § 6.1):
--   * label_return_7d        -> flow_btc_v1        (continuous regression)
--   * label_regime_risk_on_48h -> regime_btc_v1    (binary classification)
--   * label_meanrev_24h      -> positioning_btc_v1 (continuous, clipped)

INSERT INTO feature_registry (
    feature_name, version, family, owner, transformer_ref, inputs,
    output_dtype, symbols, intervals,
    pit_safe, ffill_policy, max_ffill_age_hours, backfill_strategy,
    label_for_model, label_direction, status, created_by, updated_by
) VALUES
    (
        'label_return_7d', 1, 'label', 'blueprint',
        'blackheart_ingest.features.definitions:_forward_return',
        '{"series_ids":["close_price"],"args":{"close_col":"close_price","horizon_bars":168},"raw_table":"market_data"}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], ARRAY['1h'],
        FALSE, NULL, NULL, 'recompute_from_raw',
        'flow_btc_v1', 'forward', 'registered', 'V73 migration', 'V73 migration'
    ),
    (
        'label_regime_risk_on_48h', 1, 'label', 'blueprint',
        'blackheart_ingest.features.definitions:_forward_sharpe_binary_sign',
        '{"series_ids":["close_price"],"args":{"close_col":"close_price","horizon_bars":48},"raw_table":"market_data"}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], ARRAY['1h'],
        FALSE, NULL, NULL, 'recompute_from_raw',
        'regime_btc_v1', 'forward', 'registered', 'V73 migration', 'V73 migration'
    ),
    (
        'label_meanrev_24h', 1, 'label', 'blueprint',
        'blackheart_ingest.features.definitions:_forward_meanrev_atr',
        '{"series_ids":["close_price","high_price","low_price"],"args":{"close_col":"close_price","horizon_bars":24,"atr_window":14,"clip_value":3.0},"raw_table":"market_data"}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], ARRAY['1h'],
        FALSE, NULL, NULL, 'recompute_from_raw',
        'positioning_btc_v1', 'forward', 'registered', 'V73 migration', 'V73 migration'
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
    label_for_model = EXCLUDED.label_for_model,
    label_direction = EXCLUDED.label_direction,
    status = EXCLUDED.status,
    updated_time = NOW(),
    updated_by = EXCLUDED.updated_by;
