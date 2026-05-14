-- V67 — ML ingestion control plane (admin-managed scheduler + health dashboard).
--
-- Why:
--   V66 added the ML data plane (raw tables, registries, signals). This
--   migration adds the *control plane* so admin can manage ingestion from
--   the frontend rather than only via CLI:
--     1. ml_ingest_schedule — admin-configured per-source cron schedules
--        (cron expression, lookback window, enabled flag, source-specific
--        config). Spring TaskScheduler reads this and registers dynamic
--        CronTrigger tasks; admin edits via Blackridge → schedule update
--        → Spring picks up on next refresh tick (every 60s).
--     2. ml_source_health — per-source health snapshot updated by every
--        live_ingest tick (success or failure). Drives the frontend
--        "ML Data Sources" dashboard. Three states: healthy / degraded /
--        failed. Monitor cron alerts on transitions to degraded/failed.
--
-- Pattern reuse:
--   Manual backfills run through the EXISTING historical_backfill_job
--   table (V47). Operator triggers a backfill from frontend → Java inserts
--   a PENDING row with job_type=BACKFILL_ML_<SOURCE> → async runner picks
--   it up → handler delegates to Python ingest service via HTTP →
--   status flips through RUNNING → SUCCESS/FAILED/CANCELLED. No schema
--   change for this — only new JobType enum values + handler beans on
--   the Java side. See JOBS.md for the existing dispatch contract.
--
-- BaseEntity discipline:
--   Both new tables carry the standard 4 audit columns
--   (created_time, created_by, updated_time, updated_by) per the operator
--   directive 2026-05-14 that supersedes the historical market_data /
--   feature_store exemption.
--
-- Idempotency: every CREATE uses IF NOT EXISTS. INSERTs use
--   ON CONFLICT DO NOTHING so re-running the seed is a no-op.

-- ────────────────────────────────────────────────────────────────────────
-- 1. ml_ingest_schedule — admin-configurable per-source schedule
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS ml_ingest_schedule (
    id                  BIGSERIAL    PRIMARY KEY,
    source              VARCHAR(80)  NOT NULL,
    symbol              VARCHAR(20),
    cron_expression     VARCHAR(80)  NOT NULL,
    lookback_hours      INT          NOT NULL DEFAULT 24,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    last_run_at         TIMESTAMPTZ,
    last_success_at     TIMESTAMPTZ,
    last_error_message  TEXT,
    next_run_at         TIMESTAMPTZ,
    config              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(150),
    updated_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(150),
    CONSTRAINT chk_ml_ingest_schedule_lookback
        CHECK (lookback_hours > 0 AND lookback_hours <= 720)
);

-- Uniqueness on (source, symbol) needs two partial indexes because
-- PostgreSQL treats NULL as distinct in UNIQUE constraints. A plain
-- UNIQUE (source, symbol) would allow multiple (source='fred', symbol=NULL)
-- rows — silently breaking the "one schedule per (source, symbol)" contract
-- the service relies on.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ml_ingest_schedule_source_with_symbol
    ON ml_ingest_schedule (source, symbol)
    WHERE symbol IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ml_ingest_schedule_source_null_symbol
    ON ml_ingest_schedule (source)
    WHERE symbol IS NULL;

CREATE INDEX IF NOT EXISTS idx_ml_ingest_schedule_enabled
    ON ml_ingest_schedule (enabled, next_run_at)
    WHERE enabled = TRUE;
CREATE INDEX IF NOT EXISTS idx_ml_ingest_schedule_source
    ON ml_ingest_schedule (source);

COMMENT ON TABLE ml_ingest_schedule IS
    'V67 — Admin-configurable per-source cron schedules for live ML ingestion. Spring TaskScheduler reads this on a 60s refresh tick and registers dynamic CronTriggers. Edit via Blackridge admin UI; changes take effect on next refresh. enabled=FALSE = schedule exists but does not fire (use for staged rollout).';
COMMENT ON COLUMN ml_ingest_schedule.source IS
    'V67 — Source identifier matching one of the Python source modules: ''fred'', ''binance_macro'', ''defillama'', ''coinmetrics'', ''coingecko'', ''alternative_me'', ''forexfactory''.';
COMMENT ON COLUMN ml_ingest_schedule.symbol IS
    'V67 — Symbol scope for this schedule. NULL for macro-only sources (fred, alternative_me, forexfactory). Set for symbol-specific sources (binance_macro, coinmetrics). UNIQUE constraint on (source, symbol) prevents duplicate schedules.';
COMMENT ON COLUMN ml_ingest_schedule.cron_expression IS
    'V67 — Spring 6-field cron (sec min hour day month dow). Edit via PATCH /api/v1/ml-ingest/schedule/{id}. Validated server-side via CronExpression.parse() before persist.';
COMMENT ON COLUMN ml_ingest_schedule.lookback_hours IS
    'V67 — How many hours of history to pull on each live-ingest tick. Default 24 (catches up if a tick was missed). CHECK enforces [1, 720]. Backfills (historical_backfill_job) ignore this column and use explicit range.';
COMMENT ON COLUMN ml_ingest_schedule.config IS
    'V67 — Source-specific config as JSONB. Examples: {"series_ids": ["DXY","DGS10","VIXCLS"]} for fred, {"intervals": ["1h","4h"]} for binance_macro, {"calendars": ["FOMC"]} for forexfactory. Schema enforced by each Python source module.';

-- ────────────────────────────────────────────────────────────────────────
-- 2. ml_source_health — per-source health snapshot (frontend dashboard)
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS ml_source_health (
    source                  VARCHAR(80)  PRIMARY KEY,
    last_pull_at            TIMESTAMPTZ,
    last_success_at         TIMESTAMPTZ,
    last_failure_at         TIMESTAMPTZ,
    consecutive_failures    INT          NOT NULL DEFAULT 0,
    rows_inserted_24h       BIGINT       NOT NULL DEFAULT 0,
    errors_24h              INT          NOT NULL DEFAULT 0,
    rejected_pit_violations_24h INT      NOT NULL DEFAULT 0,
    health_status           VARCHAR(20)  NOT NULL DEFAULT 'unknown',
    health_message          TEXT,
    metrics                 JSONB,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_time            TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(150),
    updated_time            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by              VARCHAR(150),
    CONSTRAINT chk_ml_source_health_status
        CHECK (health_status IN ('healthy', 'degraded', 'failed', 'unknown', 'disabled')),
    CONSTRAINT chk_ml_source_health_failures_nonneg
        CHECK (consecutive_failures >= 0 AND errors_24h >= 0 AND rejected_pit_violations_24h >= 0)
);

CREATE INDEX IF NOT EXISTS idx_ml_source_health_status
    ON ml_source_health (health_status, updated_at DESC);

COMMENT ON TABLE ml_source_health IS
    'V67 — Per-source health snapshot updated on every live-ingest tick. One row per source (PK on source). Drives the "ML Data Sources" dashboard in Blackridge. health_status transitions: healthy ↔ degraded ↔ failed. Monitor cron alerts (Telegram) on transition to degraded or failed.';
COMMENT ON COLUMN ml_source_health.health_status IS
    'V67 — Rollup of recent health. ''healthy'' = no consecutive failures, no recent PIT violations. ''degraded'' = some failures or PIT rejections but still pulling data. ''failed'' = consecutive_failures > threshold (typically 3). ''disabled'' = ml_ingest_schedule.enabled is FALSE for all schedules of this source. ''unknown'' = never ticked.';
COMMENT ON COLUMN ml_source_health.rejected_pit_violations_24h IS
    'V67 — Count of rows rejected by PIT guards in last 24h (event_time > ingestion_time + skew, or backfill-lag too large). Non-zero is a signal that the source''s timestamps are drifting — investigate before relying on the data.';
COMMENT ON COLUMN ml_source_health.metrics IS
    'V67 — Source-specific metrics as JSONB. Example: {"avg_pull_duration_ms": 1234, "last_event_time_lag_minutes": 45, "series_count": 6}. Schema not enforced; each source contributes its own diagnostic fields.';

-- ────────────────────────────────────────────────────────────────────────
-- 3. Seed initial schedules (all DISABLED — operator opts in per source)
-- ────────────────────────────────────────────────────────────────────────
--
-- Cadence rationale per source:
--   fred              daily after FRED publishes (~22:00 UTC, US market close)
--   binance_macro     every 8h aligned with funding cycles (00, 08, 16 UTC)
--   defillama         daily; stablecoin supply updates intraday but daily
--                     snapshot is sufficient for feature_store
--   coinmetrics       daily; community-tier publishes daily
--   coingecko         every 6h; BTC dominance + market cap (rate-limited tier)
--   alternative_me    daily; F&G is a daily index
--   forexfactory      daily refresh of upcoming events (scrape-based, fragile)
--
-- All schedules begin DISABLED. Operator enables one at a time via
-- Blackridge admin UI after the Python source module is implemented +
-- smoke-tested.

-- Note: ON CONFLICT DO NOTHING (no target) covers both partial unique
-- indexes — `(source, symbol) WHERE symbol IS NOT NULL` and
-- `(source) WHERE symbol IS NULL`. Specifying a column-list target would
-- require two separate INSERTs since each partial index handles a
-- different subset.
INSERT INTO ml_ingest_schedule (source, symbol, cron_expression, lookback_hours, enabled, config, created_by) VALUES
    ('fred',            NULL,        '0 0 22 * * *',  48,  FALSE, '{"series_ids":["DTWEXBGS","DFII10","DGS10","DGS2","T10Y2Y","VIXCLS","M2SL","CPIAUCSL"],"use_alfred_vintage":true}'::jsonb, 'V67 migration'),
    ('binance_macro',   'BTCUSDT',   '0 0 0/8 * * *', 24,  FALSE, '{"feeds":["funding_rate","open_interest","top_long_short_ratio","taker_buy_sell"],"intervals":["1h","4h"]}'::jsonb, 'V67 migration'),
    ('defillama',       NULL,        '0 30 0 * * *',  48,  FALSE, '{"stablecoins":["USDT","USDC"],"chains":["Ethereum","Tron","BSC"]}'::jsonb, 'V67 migration'),
    ('coinmetrics',     'BTCUSDT',   '0 0 1 * * *',   72,  FALSE, '{"metrics":["FlowOutNative","AdrActCnt","TxCnt","CapRealUSD","CapMrktCurUSD"]}'::jsonb, 'V67 migration'),
    ('coingecko',       NULL,        '0 0 0/6 * * *', 12,  FALSE, '{"global_metrics":true,"per_coin":["bitcoin","ethereum"]}'::jsonb, 'V67 migration'),
    ('alternative_me',  NULL,        '0 15 0 * * *',  48,  FALSE, '{"index":"fear_and_greed"}'::jsonb, 'V67 migration'),
    ('forexfactory',    NULL,        '0 30 1 * * *',  168, FALSE, '{"calendars":["FOMC","CPI","NFP","Unemployment"],"impact_filter":["High","Medium"]}'::jsonb, 'V67 migration')
ON CONFLICT DO NOTHING;

-- ────────────────────────────────────────────────────────────────────────
-- 4. Seed initial health rows (one per source, status='unknown')
-- ────────────────────────────────────────────────────────────────────────

INSERT INTO ml_source_health (source, health_status, health_message, created_by) VALUES
    ('fred',            'unknown', 'Awaiting first ingestion tick.', 'V67 migration'),
    ('binance_macro',   'unknown', 'Awaiting first ingestion tick.', 'V67 migration'),
    ('defillama',       'unknown', 'Awaiting first ingestion tick.', 'V67 migration'),
    ('coinmetrics',     'unknown', 'Awaiting first ingestion tick.', 'V67 migration'),
    ('coingecko',       'unknown', 'Awaiting first ingestion tick.', 'V67 migration'),
    ('alternative_me',  'unknown', 'Awaiting first ingestion tick.', 'V67 migration'),
    ('forexfactory',    'unknown', 'Awaiting first ingestion tick.', 'V67 migration')
ON CONFLICT (source) DO NOTHING;

-- ────────────────────────────────────────────────────────────────────────
-- 5. Role grants (extend V66's role separation)
-- ────────────────────────────────────────────────────────────────────────
--
-- blackheart_trading: writes both tables (live_ingest worker on VPS
--   updates health, scheduler service writes last_run_at / next_run_at).
-- blackheart_research: reads both (heavy_ingest can read schedule for
--   coordination; no writes).

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_trading') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON ml_ingest_schedule TO blackheart_trading';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON ml_source_health TO blackheart_trading';
        EXECUTE 'GRANT USAGE, SELECT, UPDATE ON SEQUENCE ml_ingest_schedule_id_seq TO blackheart_trading';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_research') THEN
        EXECUTE 'GRANT SELECT ON ml_ingest_schedule TO blackheart_research';
        EXECUTE 'GRANT SELECT ON ml_source_health TO blackheart_research';
    END IF;
END $$;
