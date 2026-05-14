-- V74 — Seed feature_registry with label_triple_barrier v1, completing
-- blueprint § 5.6. This is the canonical López de Prado labeler for the
-- directional sub-model.
--
-- Parameters (matching § 5.6 defaults):
--   k_tp        = 1.5  (TP at +1.5 ATR — positive skew bias)
--   k_sl        = 1.0  (SL at -1.0 ATR)
--   horizon     = 24 bars on 1h (medium horizon)
--   atr_window  = 14
--   intra-bar fill: conservative — if both barriers hit in same bar, SL wins
--
-- Output classes:
--   +1   TP hit first
--   -1   SL hit first OR both barriers hit in same bar (conservative)
--    0   horizon elapsed with neither hit
--   NaN  insufficient future data (last horizon_bars rows of input) OR
--        ATR undefined (warmup)
--
-- Inputs JSONB shape mirrors V70/V71/V72/V73:
--   {"series_ids":[...], "args":{...}, "raw_table":"market_data"}

INSERT INTO feature_registry (
    feature_name, version, family, owner, transformer_ref, inputs,
    output_dtype, symbols, intervals,
    pit_safe, ffill_policy, max_ffill_age_hours, backfill_strategy,
    label_for_model, label_direction, status, created_by, updated_by
) VALUES
    (
        'label_triple_barrier', 1, 'label', 'blueprint',
        'blackheart_ingest.features.definitions:_forward_triple_barrier',
        '{"series_ids":["close_price","high_price","low_price"],"args":{"close_col":"close_price","high_col":"high_price","low_col":"low_price","horizon_bars":24,"k_tp":1.5,"k_sl":1.0,"atr_window":14},"raw_table":"market_data"}'::jsonb,
        'numeric', ARRAY['BTCUSDT'], ARRAY['1h'],
        FALSE, NULL, NULL, 'recompute_from_raw',
        'directional_btc_v1', 'forward', 'registered', 'V74 migration', 'V74 migration'
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
