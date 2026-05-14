-- V70 — Feature values storage + seed feature_registry with starter features.
--
-- Why:
--   M3 of the ML/sentiment blueprint requires somewhere to land computed
--   feature values that downstream training and inference layers can join
--   against. macro_raw holds publisher events; feature_values is the
--   derived layer (passthroughs, rolling stats, z-scores, etc.) anchored
--   to the same event_time axis.
--
--   The compute engine in blackheart_ingest.features.compute returns tidy
--   pandas DataFrames; the new feature_values table is its persistent
--   sink. feature_compute_run (created in V66) is updated by the same
--   worker so every batch of values is traceable to a run row.
--
-- Schema choices:
--   - Tall storage (one row per feature×ts) — not wide columns like the
--     existing feature_store (which is OHLCV-coupled). Lets us add new
--     features without ALTERs and supports per-feature backfill /
--     recompute / kill without affecting siblings.
--   - symbol + interval as NOT NULL '' sentinels rather than NULL so the
--     primary key index works without partial-unique gymnastics. Macro
--     features (VIX, DXY) get symbol='' interval=''; per-bar features get
--     real values.
--   - Partitioned monthly by ts with a DEFAULT partition to absorb
--     deep-history backfills exactly like macro_raw (V69 pattern).
--   - FK to feature_registry guarantees a name+version combo can't be
--     written without registry metadata.
--
-- Seeded features:
--   vix_close v1 — passthrough VIXCLS from FRED
--   dxy_close v1 — passthrough DTWEXBGS from FRED
--   dxy_zscore_30d v1 — 30d rolling z-score of DTWEXBGS
--
--   These match the starter set in
--   src/blackheart_ingest/features/definitions.py.

-- ─────────────────────────────────────────────────────────────────────────
-- GROUP A — feature_values storage
-- ─────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS feature_values (
    feature_name    VARCHAR(120) NOT NULL,
    version         INT          NOT NULL,
    symbol          VARCHAR(20)  NOT NULL DEFAULT '',
    interval        VARCHAR(10)  NOT NULL DEFAULT '',
    ts              TIMESTAMPTZ  NOT NULL,
    value           NUMERIC(28, 10),
    value_text      TEXT,
    compute_run_id  UUID         NOT NULL,
    created_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(150),
    updated_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(150),
    PRIMARY KEY (feature_name, version, symbol, interval, ts)
) PARTITION BY RANGE (ts);

CREATE INDEX IF NOT EXISTS idx_feature_values_name_ts
    ON feature_values (feature_name, version, ts DESC);
CREATE INDEX IF NOT EXISTS idx_feature_values_symbol_ts
    ON feature_values (symbol, ts DESC) WHERE symbol <> '';
CREATE INDEX IF NOT EXISTS idx_feature_values_run
    ON feature_values (compute_run_id);

-- Reject rows with both value AND value_text NULL — no-info rows are
-- always a bug in the producing transformer. Guarded via DO block so the
-- migration is idempotent when re-run.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_feature_values_value_or_text'
    ) THEN
        ALTER TABLE feature_values
            ADD CONSTRAINT chk_feature_values_value_or_text
            CHECK (value IS NOT NULL OR value_text IS NOT NULL);
    END IF;
END $$;

-- Monthly partitions 2024-12 .. 2027-12 + DEFAULT (matches macro_raw / V69).
DO $$
DECLARE
    d DATE := DATE '2024-12-01';
    end_d DATE := DATE '2028-01-01';
    next_d DATE;
    part_name TEXT;
BEGIN
    WHILE d < end_d LOOP
        next_d := d + INTERVAL '1 month';
        part_name := 'feature_values_' || TO_CHAR(d, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF feature_values '
            'FOR VALUES FROM (%L) TO (%L);',
            part_name, d, next_d
        );
        d := next_d;
    END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS feature_values_default
    PARTITION OF feature_values DEFAULT;

COMMENT ON TABLE feature_values IS
    'V70 — Derived feature values keyed by (feature_name, version, symbol, interval, ts). Tall storage so new features ship without ALTERs. Partitioned monthly on ts with a DEFAULT partition for deep-history backfills.';
COMMENT ON COLUMN feature_values.ts IS
    'V70 — Anchor timestamp the feature value applies to. For macro features this equals the publisher event_time. For per-bar derived features this is the bar start_time.';
COMMENT ON COLUMN feature_values.symbol IS
    'V70 — Symbol scope (e.g. BTCUSDT) or '''' for global features. NOT NULL so the PK is straightforward; empty string is the documented sentinel.';
COMMENT ON COLUMN feature_values.interval IS
    'V70 — Bar interval (e.g. 1h, 4h) or '''' when the feature isn''t per-bar.';

-- ─────────────────────────────────────────────────────────────────────────
-- GROUP B — feature_registry seed for the M3 starter set
-- ─────────────────────────────────────────────────────────────────────────

-- transformer_ref is the "module:callable" path of the factory in
-- blackheart_ingest.features.definitions. inputs JSON carries the args
-- (series_ids + any tunables) so a future loader can reconstruct the
-- transformer from registry rows alone.
--
-- ON CONFLICT DO UPDATE so re-runs of V70 heal earlier seeds that used
-- the human-readable but non-importable form. Idempotent in both
-- directions: cold install AND repair of an already-applied registry.
INSERT INTO feature_registry (
    feature_name, version, family, owner, transformer_ref, inputs,
    output_dtype, symbols, intervals,
    pit_safe, ffill_policy, max_ffill_age_hours, backfill_strategy,
    label_for_model, label_direction, status, created_by, updated_by
) VALUES
    (
        'vix_close', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_passthrough',
        '{"series_ids":["VIXCLS"],"args":{"col":"VIXCLS"}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V70 migration', 'V70 migration'
    ),
    (
        'dxy_close', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_passthrough',
        '{"series_ids":["DTWEXBGS"],"args":{"col":"DTWEXBGS"}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V70 migration', 'V70 migration'
    ),
    (
        'dxy_zscore_30d', 1, 'macro', 'blueprint',
        'blackheart_ingest.features.definitions:_rolling_zscore',
        '{"series_ids":["DTWEXBGS"],"args":{"col":"DTWEXBGS","window":30,"min_periods":10}}'::jsonb,
        'numeric', NULL, NULL,
        TRUE, 'last_value', 72, 'recompute_from_raw',
        NULL, NULL, 'registered', 'V70 migration', 'V70 migration'
    )
ON CONFLICT (feature_name, version) DO UPDATE SET
    family = EXCLUDED.family,
    owner = EXCLUDED.owner,
    transformer_ref = EXCLUDED.transformer_ref,
    inputs = EXCLUDED.inputs,
    output_dtype = EXCLUDED.output_dtype,
    pit_safe = EXCLUDED.pit_safe,
    ffill_policy = EXCLUDED.ffill_policy,
    max_ffill_age_hours = EXCLUDED.max_ffill_age_hours,
    backfill_strategy = EXCLUDED.backfill_strategy,
    status = EXCLUDED.status,
    updated_time = NOW(),
    updated_by = EXCLUDED.updated_by;

-- Foreign key on the partitioned table referencing feature_registry. PG 15+
-- supports this directly. Added after both tables exist.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_feature_values_feature'
    ) THEN
        ALTER TABLE feature_values
            ADD CONSTRAINT fk_feature_values_feature
            FOREIGN KEY (feature_name, version)
            REFERENCES feature_registry (feature_name, version);
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────
-- GROUP C — Role grants (mirror V66 pattern with pg_roles existence guard)
-- ─────────────────────────────────────────────────────────────────────────

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_trading') THEN
        -- Compute worker runs as blackheart_trading; needs RW on values +
        -- run audit, RO on registry (seeded via migration only).
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON feature_values TO blackheart_trading';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON feature_compute_run TO blackheart_trading';
        EXECUTE 'GRANT SELECT ON feature_registry TO blackheart_trading';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_research') THEN
        -- Research reads features; mirrors V66 grant on the other ML tables.
        EXECUTE 'GRANT SELECT ON feature_values TO blackheart_research';
    END IF;
END $$;
