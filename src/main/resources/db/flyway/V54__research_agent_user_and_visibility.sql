-- V54 — Dedicated research-agent user/account + account_strategy visibility flag.
--
-- Why:
--   Until now the research-orchestrator authenticated as the first user in
--   the DB (admin) via /api/v1/dev/login-as. All research backtests landed on
--   admin's account_strategy rows, and tick.py's _resolve_account_strategy
--   ran "WHERE strategy_code=$1 LIMIT 1" with no account scoping. This both
--   conflated research with admin's live capital and prevented surfacing
--   research output to other tenants.
--
-- This migration:
--   1. Provisions a dedicated research-agent user + account (pinned UUIDs so
--      the orchestrator can reference them via ORCH_RESEARCH_ACCOUNT_ID).
--   2. Adds account_strategy.visibility (PRIVATE | PUBLIC); default PRIVATE
--      keeps every existing row tenant-private.
--   3. Copies (does not move) every (strategy_code, symbol, interval_name)
--      tuple that has been touched by research_iteration_log or research_queue
--      into the agent's account, marked PUBLIC, simulated, disabled. Admin's
--      live-trading rows are untouched.
--   4. Copies the active strategy_param row for each cloned account_strategy
--      so cloned strategies carry the same param overrides forward.
--
-- Idempotent: re-runs are no-ops because user/account inserts use ON CONFLICT
-- and the account_strategy / strategy_param copies use WHERE NOT EXISTS.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. research-agent user
-- ─────────────────────────────────────────────────────────────────────────────
-- Pinned UUID: 99999999-9999-9999-9999-000000000001
-- password_hash is a deliberately-locked placeholder that follows the bcrypt
-- $2a$10$<22-char salt><31-char hash> wire format (60 chars total) so that
-- BCryptPasswordEncoder.matches() returns false instead of throwing on the
-- first login attempt. The orchestrator never logs in via password;
-- operators set ORCH_JVM_SERVICE_PASSWORD out-of-band and update this hash
-- with the matching bcrypt before flipping the orchestrator to
-- service_account auth. Until then nobody can log in as the agent.
INSERT INTO users (
    user_id, email, password_hash, full_name, role, status,
    email_verified, created_time, created_by, updated_time, updated_by
)
VALUES (
    '99999999-9999-9999-9999-000000000001',
    'research-agent@blackheart.local',
    '$2a$10$LockedPlaceholderSalt2LockedHashNeverMatchesPasswordX',
    'Research Agent',
    'USER',
    'ACTIVE',
    TRUE,
    NOW(),
    'flyway:V54',
    NOW(),
    'flyway:V54'
)
ON CONFLICT (email) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. research-agent account
-- ─────────────────────────────────────────────────────────────────────────────
-- Pinned UUID: 99999999-9999-9999-9999-000000000002 (set ORCH_RESEARCH_ACCOUNT_ID
-- to this value).
-- exchange='BIN' to satisfy the existing CHECK / app-side regex; api_key/secret
-- are placeholder strings - this account never trades real capital.
INSERT INTO accounts (
    account_id, username, is_active, exchange,
    take_profit, stop_loss, api_key, api_secret, user_id,
    max_concurrent_longs, max_concurrent_shorts,
    created_time, created_by, updated_time, updated_by
)
VALUES (
    '99999999-9999-9999-9999-000000000002',
    'research-agent',
    'N',
    'BIN',
    0,
    0,
    'research-agent-placeholder-api-key-not-used-for-trading-EVER-x',
    'research-agent-placeholder-api-secret-not-used-for-trading-EVER',
    '99999999-9999-9999-9999-000000000001',
    0,
    0,
    NOW(),
    'flyway:V54',
    NOW(),
    'flyway:V54'
)
ON CONFLICT (account_id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. account_strategy.visibility
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE account_strategy
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';

-- Add CHECK constraint separately so re-runs don't fail with "constraint already
-- exists" (ALTER ... ADD CHECK has no IF NOT EXISTS form on PG <14 paths used
-- here).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_account_strategy_visibility'
    ) THEN
        ALTER TABLE account_strategy
            ADD CONSTRAINT chk_account_strategy_visibility
            CHECK (visibility IN ('PRIVATE', 'PUBLIC'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_account_strategy_public
    ON account_strategy (visibility)
    WHERE visibility = 'PUBLIC' AND is_deleted = FALSE;

COMMENT ON COLUMN account_strategy.visibility IS
    'PRIVATE: visible only to the owning user. PUBLIC: visible to all users so they can clone the preset into their own account. Backtest/edit/enable still requires ownership regardless of visibility.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Copy historic research strategies onto the agent account, marked PUBLIC
-- ─────────────────────────────────────────────────────────────────────────────
-- Source: any account_strategy row whose strategy_code appears in either
-- research_iteration_log or research_queue. We pick the most recently-updated
-- non-deleted source row per (strategy_code, symbol, interval_name) tuple.
-- The agent's clones are forced enabled=false / simulated=true / status=STOPPED
-- so they never auto-trade; visibility=PUBLIC so all users can see + clone them.
WITH research_codes AS (
    SELECT DISTINCT strategy_code FROM research_iteration_log
    UNION
    SELECT DISTINCT strategy_code FROM research_queue
),
ranked_sources AS (
    SELECT
        acs.*,
        ROW_NUMBER() OVER (
            PARTITION BY acs.strategy_code, acs.symbol, acs.interval_name
            ORDER BY acs.updated_time DESC, acs.created_time DESC
        ) AS rn
    FROM account_strategy acs
    JOIN research_codes rc ON rc.strategy_code = acs.strategy_code
    WHERE acs.is_deleted = FALSE
      AND acs.account_id <> '99999999-9999-9999-9999-000000000002'
)
INSERT INTO account_strategy (
    account_strategy_id, account_id, strategy_definition_id, strategy_code,
    preset_name, symbol, interval_name,
    enabled, allow_long, allow_short,
    max_open_positions, capital_allocation_pct, priority_order,
    current_status, is_deleted,
    dd_kill_threshold_pct, is_kill_switch_tripped, simulated,
    regime_gate_enabled, allowed_trend_regimes, allowed_volatility_regimes,
    kelly_sizing_enabled, kelly_max_fraction,
    visibility, version,
    created_time, created_by, updated_time, updated_by
)
SELECT
    gen_random_uuid(),
    '99999999-9999-9999-9999-000000000002',
    rs.strategy_definition_id,
    rs.strategy_code,
    COALESCE(rs.preset_name, 'agent-default'),
    rs.symbol,
    rs.interval_name,
    FALSE,                                  -- enabled
    rs.allow_long,
    rs.allow_short,
    rs.max_open_positions,
    rs.capital_allocation_pct,
    1,                                      -- priority_order (single account, just slot at 1)
    'STOPPED',
    FALSE,                                  -- is_deleted
    rs.dd_kill_threshold_pct,
    FALSE,                                  -- is_kill_switch_tripped
    TRUE,                                   -- simulated
    rs.regime_gate_enabled,
    rs.allowed_trend_regimes,
    rs.allowed_volatility_regimes,
    rs.kelly_sizing_enabled,
    rs.kelly_max_fraction,
    'PUBLIC',
    0,                                      -- @Version starts at 0
    NOW(), 'flyway:V54', NOW(), 'flyway:V54'
FROM ranked_sources rs
WHERE rs.rn = 1
  AND NOT EXISTS (
      SELECT 1 FROM account_strategy existing
      WHERE existing.account_id = '99999999-9999-9999-9999-000000000002'
        AND existing.strategy_code = rs.strategy_code
        AND existing.symbol = rs.symbol
        AND existing.interval_name = rs.interval_name
        AND existing.is_deleted = FALSE
  );

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. Copy the active strategy_param row for each cloned strategy
-- ─────────────────────────────────────────────────────────────────────────────
-- For every agent-owned account_strategy that doesn't yet have a strategy_param
-- row, copy the active param row from the source we cloned from (matched by
-- strategy_code+symbol+interval_name).
INSERT INTO strategy_param (
    param_id, account_strategy_id, name, param_overrides,
    is_active, is_deleted, source_backtest_run_id, version,
    created_time, created_by, updated_time, updated_by
)
SELECT
    gen_random_uuid(),
    agent_acs.account_strategy_id,
    COALESCE(src_param.name, 'agent-default'),
    src_param.param_overrides,
    TRUE,
    FALSE,
    src_param.source_backtest_run_id,
    0,
    NOW(), 'flyway:V54', NOW(), 'flyway:V54'
FROM account_strategy agent_acs
JOIN LATERAL (
    SELECT sp.*
    FROM strategy_param sp
    JOIN account_strategy src_acs ON src_acs.account_strategy_id = sp.account_strategy_id
    WHERE src_acs.strategy_code = agent_acs.strategy_code
      AND src_acs.symbol = agent_acs.symbol
      AND src_acs.interval_name = agent_acs.interval_name
      AND src_acs.account_id <> '99999999-9999-9999-9999-000000000002'
      AND src_acs.is_deleted = FALSE
      AND sp.is_active = TRUE
      AND sp.is_deleted = FALSE
    ORDER BY sp.updated_time DESC
    LIMIT 1
) src_param ON TRUE
WHERE agent_acs.account_id = '99999999-9999-9999-9999-000000000002'
  AND agent_acs.is_deleted = FALSE
  AND NOT EXISTS (
      SELECT 1 FROM strategy_param existing
      WHERE existing.account_strategy_id = agent_acs.account_strategy_id
        AND existing.is_deleted = FALSE
  );
