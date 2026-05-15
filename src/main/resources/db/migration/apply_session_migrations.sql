-- ─────────────────────────────────────────────────────────────────────────
-- Consolidated session migrations — bundled 2026-04-26
-- ─────────────────────────────────────────────────────────────────────────
-- One-shot script that applies every column / index introduced during the
-- fund-grade discipline build (Phases 2a → 2c, 3.8, plus the reproducibility
-- + PSR + holdout schema for the backtest/sweep stack).
--
-- Idempotent: every statement uses IF NOT EXISTS / CREATE INDEX IF NOT EXISTS,
-- so re-running on a partially-migrated DB is safe.
--
-- Wraps in a single transaction — if any statement fails, none take effect.
-- That's the safest default; if you'd rather apply piecemeal, run the
-- individual files in this directory in this exact order:
--   1. add_backtest_run_reproducibility.sql
--   2. add_backtest_run_psr.sql
--   3. add_backtest_run_holdout.sql
--   4. add_risk_guard_columns.sql
--   5. add_book_vol_targeting.sql
--   6. add_trade_attribution_columns.sql
--
-- Apply with:
--   psql -h localhost -U postgres -d trading_db \
--        -f src/main/resources/db/migration/apply_session_migrations.sql
-- ─────────────────────────────────────────────────────────────────────────

BEGIN;

-- ── 1. Reproducibility manifest on backtest_run (Phase 2 of Phase 1) ─────
-- git_commit_sha + app_version stamped at submit time so a result can be
-- replayed identically months later.
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS git_commit_sha VARCHAR(40),
    ADD COLUMN IF NOT EXISTS app_version    VARCHAR(50);

-- ── 2. Probabilistic Sharpe Ratio on backtest_run (Phase 3 of Phase 1) ───
-- Computed at run completion; null on PENDING / RUNNING / FAILED. Drives
-- the per-run PSR card and the cohort-level DSR threshold on sweeps.
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS psr NUMERIC(10, 6);

-- ── 3. Locked-holdout protection on backtest_run (Phase 4b of Phase 1) ───
-- Tracks the one-shot holdout evaluation per sweep. Unique partial index
-- enforces "at most one holdout run per sweep" at the DB level — even a
-- service-layer race can't double-evaluate.
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS is_holdout_run       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS holdout_for_sweep_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS idx_backtest_run_holdout_per_sweep
    ON backtest_run (holdout_for_sweep_id)
    WHERE is_holdout_run = TRUE;

-- ── 4. Risk guard — DD kill-switch + concurrency caps (Phase 2a) ─────────
-- Sticky kill switch on each strategy + per-account concurrency caps.
-- RiskGuardService runs both checks before every OPEN_LONG / OPEN_SHORT.
ALTER TABLE account_strategy
    ADD COLUMN IF NOT EXISTS dd_kill_threshold_pct  NUMERIC(5, 2) NOT NULL DEFAULT 25.00,
    ADD COLUMN IF NOT EXISTS is_kill_switch_tripped BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS kill_switch_tripped_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS kill_switch_reason     TEXT;

ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS max_concurrent_longs  INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN IF NOT EXISTS max_concurrent_shorts INTEGER NOT NULL DEFAULT 2;

-- ── 5. Vol-targeted book sizing toggle + target (Phase 2b) ───────────────
-- Off by default — opt-in. When enabled, BookVolTargetingService scales
-- entry sizes so realized strategy vol hits target; legacy riskAmount ×
-- balance path applies otherwise.
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS vol_targeting_enabled BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS book_vol_target_pct   NUMERIC(5, 2) NOT NULL DEFAULT 15.00;

-- ── 6. Trade attribution intent capture (Phase 2c) ───────────────────────
-- Pre-vol-targeting size + decision-time price stamped on every Trade row
-- at open. TradeAttributionService uses these at close to decompose
-- realized P&L into signal_alpha + execution_drift + sizing_residual.
ALTER TABLE trades
    ADD COLUMN IF NOT EXISTS intended_entry_price NUMERIC(24, 12),
    ADD COLUMN IF NOT EXISTS intended_size        NUMERIC(24, 12),
    ADD COLUMN IF NOT EXISTS decision_time        TIMESTAMP;

-- ── 7. Backtest-trade attribution intent (Phase 2c followup, fix #3) ─────
-- Mirrors columns 6 onto the backtest_trade table so attribution math
-- works for backtest trades too. In backtest mode intended_size equals
-- decision-time size (no vol-targeting on the backtest path), so sizing
-- residual is always 0 and the only non-zero "drift" is simulated
-- slippage — useful for sanity-checking the slippage model.
ALTER TABLE backtest_trade
    ADD COLUMN IF NOT EXISTS intended_entry_price NUMERIC(24, 12),
    ADD COLUMN IF NOT EXISTS intended_size        NUMERIC(24, 12),
    ADD COLUMN IF NOT EXISTS decision_time        TIMESTAMP;

-- ── 8. Phase A — multi-strategy backtest controls ────────────────────────
-- max_concurrent_strategies caps how many trades can be open at once
-- across all strategies in a single run. With the current single-trade
-- execution model the effective cap is 1; values >1 become meaningful
-- once Phase B's multi-interval / multi-trade state lands.
-- strategy_allocations is the optional per-run override of each
-- strategy's capital allocation; key = uppercase strategy code, value =
-- allocation % (0–100). Strategies missing from the map fall back to
-- account_strategy.capital_allocation_pct.
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS max_concurrent_strategies INTEGER,
    ADD COLUMN IF NOT EXISTS strategy_allocations      JSONB;

-- ── 9. Phase B2 — per-strategy interval map ──────────────────────────────
-- Key = uppercase strategy code, value = interval string (e.g. "15m").
-- When non-null, BacktestCoordinatorService loads N candle streams (one
-- per unique interval) and each strategy fires only on its own
-- timeframe's bar closes. When null, all strategies share the run's
-- primary interval — no behavior change for legacy single-timeframe runs.
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS strategy_intervals JSONB;

-- ── 10. Account-level total concurrent-trade cap ─────────────────────────
-- Total concurrent open trades across all strategies on this account
-- (both directions combined). Null = no total cap (per-direction
-- max_concurrent_longs / max_concurrent_shorts apply alone). When set,
-- the live orchestrator gates new entries on (active+pending) < this
-- value before any strategy-specific check runs. Bounds [0, 20]
-- enforced at request time — service writes null when the wire value
-- is < 1 ("clear the cap").
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS max_concurrent_trades INTEGER;

-- ── 11. Audit-event log (Phase 1 ops hardening) ──────────────────────────
-- Append-only forensic record of who-did-what-to-which-entity. Writes
-- happen in the same transaction as the audited mutation, so a rollback
-- on the mutation rolls back the audit row too — no "we logged it but
-- it didn't happen" mismatches.
CREATE TABLE IF NOT EXISTS audit_event (
    audit_event_id  UUID         PRIMARY KEY,
    actor_user_id   UUID,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(100),
    entity_id       UUID,
    before_data     JSONB,
    after_data      JSONB,
    reason          VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_event_actor_created
    ON audit_event (actor_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_event_entity_created
    ON audit_event (entity_type, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_event_created
    ON audit_event (created_at DESC);

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────
-- Post-apply sanity check (run manually if you want to verify):
--
--   SELECT column_name FROM information_schema.columns
--    WHERE table_name = 'backtest_run'
--      AND column_name IN ('git_commit_sha', 'app_version', 'psr',
--                          'is_holdout_run', 'holdout_for_sweep_id');
--   -- expect 5 rows
--
--   SELECT column_name FROM information_schema.columns
--    WHERE table_name = 'account_strategy'
--      AND column_name IN ('dd_kill_threshold_pct', 'is_kill_switch_tripped',
--                          'kill_switch_tripped_at', 'kill_switch_reason');
--   -- expect 4 rows
--
--   SELECT column_name FROM information_schema.columns
--    WHERE table_name = 'accounts'
--      AND column_name IN ('max_concurrent_longs', 'max_concurrent_shorts',
--                          'vol_targeting_enabled', 'book_vol_target_pct');
--   -- expect 4 rows
--
--   SELECT column_name FROM information_schema.columns
--    WHERE table_name = 'trades'
--      AND column_name IN ('intended_entry_price', 'intended_size', 'decision_time');
--   -- expect 3 rows
-- ─────────────────────────────────────────────────────────────────────────
