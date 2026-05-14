-- V64 — Seed the TEST strategy for end-to-end execution validation.
--
-- Why:
--   ExecutionTestService (strategy_code="TEST") is a complete strategy
--   implementation already registered in StrategyExecutorFactory, but no
--   strategy_definition row exists and no account_strategy binds it to an
--   account. This migration wires it to the starsky / rfkih23 account in
--   PAPER_TRADE state across 4 intervals × 2 sides, covering all four exit
--   structures (SINGLE, TP1_RUNNER, TP1_TP2_RUNNER, RUNNER_ONLY).
--
-- Coverage matrix (8 rows, all PAPER_TRADE):
--   |  # | interval | side  | exit structure       | source            |
--   |----|----------|-------|----------------------|-------------------|
--   |  1 | 15m      | LONG  | SINGLE               | interval default  |
--   |  2 | 15m      | SHORT | SINGLE               | interval default  |
--   |  3 | 1h       | LONG  | TP1_RUNNER           | interval default  |
--   |  4 | 1h       | SHORT | TP1_RUNNER           | interval default  |
--   |  5 | 4h       | LONG  | TP1_TP2_RUNNER       | interval default  |
--   |  6 | 4h       | SHORT | TP1_TP2_RUNNER       | interval default  |
--   |  7 | 5m       | LONG  | RUNNER_ONLY          | preset override   |
--   |  8 | 5m       | SHORT | RUNNER_ONLY          | preset override   |
--
-- Concurrency / ordering note:
--   LONG and SHORT rows on the same interval coexist, but the orchestrator's
--   first-opener-wins rule means LONG rows (priority 50) preempt SHORT rows
--   (priority 60) on every entry. To exercise SHORT scenarios, the operator
--   flips the enabled flag:
--     UPDATE account_strategy SET enabled = NOT enabled
--      WHERE strategy_code = 'TEST'
--        AND account_id = '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2';
--   (Single statement; toggles all 8 rows.)
--
-- PAPER_TRADE state implications:
--   - strategy_definition.enabled = true, simulated = true
--   - account_strategy.enabled = true, simulated = true
--   - Live OPEN_* signals divert to paper_trade_run, NO Binance orders.
--   - To go live (real Binance orders) later, promote both scopes via
--     POST /api/v1/strategy-promotion/{id}/promote with toState=PROMOTED.
--
-- Idempotent: every INSERT uses WHERE NOT EXISTS or ON CONFLICT DO NOTHING.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. strategy_definition row
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO strategy_definition (
    strategy_definition_id, strategy_code, strategy_name, strategy_type,
    description, status, archetype, archetype_version, spec_jsonb,
    spec_schema_version, is_deleted, enabled, simulated,
    created_time, created_by, updated_time, updated_by
)
VALUES (
    '7e574e57-7e57-7e57-7e57-7e577e577e57',
    'TEST',
    'Execution Test',
    'EXECUTION_TEST',
    'End-to-end execution rig. ATR-based SL (1.5x), TP1 (2.0x), TP2 (3.5x), '
        || 'trailing stop (1.2x after 2.0x move), break-even shift after 1.0x. '
        || 'Exit structure dispatched by interval (5m/15m→SINGLE, 1h→TP1_RUNNER, '
        || '4h→TP1_TP2_RUNNER) or overridden via strategy_param.param_overrides.'
        || 'exitStructure. Not for profitability — for validating order, fill, '
        || 'multi-leg, SL/TP, and listener-close machinery.',
    'ACTIVE',
    'LEGACY_JAVA',
    1,
    NULL,
    1,
    FALSE,
    TRUE,
    TRUE,
    NOW(),
    'flyway:V64',
    NOW(),
    'flyway:V64'
)
ON CONFLICT (strategy_definition_id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. account_strategy rows (8) — all PAPER_TRADE
--    LONGs at priority_order=50, SHORTs at priority_order=60 so LONG wins
--    the first-opener-wins race on each interval until the operator toggles
--    enabled to test SHORT scenarios.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO account_strategy (
    account_strategy_id, account_id, strategy_definition_id, strategy_code,
    preset_name, symbol, interval_name,
    enabled, allow_long, allow_short, max_open_positions,
    capital_allocation_pct, priority_order, current_status,
    is_deleted, simulated,
    kill_switch_gate_enabled, regime_gate_enabled,
    correlation_gate_enabled, concurrent_cap_gate_enabled,
    created_time, created_by, updated_time, updated_by, version
)
VALUES
    -- 15m LONG → SINGLE
    ('7e570001-0000-0000-0000-000000000001', '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2',
     '7e574e57-7e57-7e57-7e57-7e577e577e57', 'TEST', 'test_15m_long',
     'BTCUSDT', '15m', TRUE, TRUE, FALSE, 1, 20.0000, 50, 'STOPPED',
     FALSE, TRUE, FALSE, FALSE, FALSE, FALSE,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64', 0),
    -- 15m SHORT → SINGLE
    ('7e570001-0000-0000-0000-000000000002', '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2',
     '7e574e57-7e57-7e57-7e57-7e577e577e57', 'TEST', 'test_15m_short',
     'BTCUSDT', '15m', TRUE, FALSE, TRUE, 1, 20.0000, 60, 'STOPPED',
     FALSE, TRUE, FALSE, FALSE, FALSE, FALSE,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64', 0),
    -- 1h LONG → TP1_RUNNER
    ('7e570001-0000-0000-0000-000000000003', '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2',
     '7e574e57-7e57-7e57-7e57-7e577e577e57', 'TEST', 'test_1h_long',
     'BTCUSDT', '1h', TRUE, TRUE, FALSE, 1, 20.0000, 50, 'STOPPED',
     FALSE, TRUE, FALSE, FALSE, FALSE, FALSE,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64', 0),
    -- 1h SHORT → TP1_RUNNER
    ('7e570001-0000-0000-0000-000000000004', '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2',
     '7e574e57-7e57-7e57-7e57-7e577e577e57', 'TEST', 'test_1h_short',
     'BTCUSDT', '1h', TRUE, FALSE, TRUE, 1, 20.0000, 60, 'STOPPED',
     FALSE, TRUE, FALSE, FALSE, FALSE, FALSE,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64', 0),
    -- 4h LONG → TP1_TP2_RUNNER
    ('7e570001-0000-0000-0000-000000000005', '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2',
     '7e574e57-7e57-7e57-7e57-7e577e577e57', 'TEST', 'test_4h_long',
     'BTCUSDT', '4h', TRUE, TRUE, FALSE, 1, 20.0000, 50, 'STOPPED',
     FALSE, TRUE, FALSE, FALSE, FALSE, FALSE,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64', 0),
    -- 4h SHORT → TP1_TP2_RUNNER
    ('7e570001-0000-0000-0000-000000000006', '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2',
     '7e574e57-7e57-7e57-7e57-7e577e577e57', 'TEST', 'test_4h_short',
     'BTCUSDT', '4h', TRUE, FALSE, TRUE, 1, 20.0000, 60, 'STOPPED',
     FALSE, TRUE, FALSE, FALSE, FALSE, FALSE,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64', 0),
    -- 5m LONG → RUNNER_ONLY (via param override)
    ('7e570001-0000-0000-0000-000000000007', '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2',
     '7e574e57-7e57-7e57-7e57-7e577e577e57', 'TEST', 'test_5m_long_runner_only',
     'BTCUSDT', '5m', TRUE, TRUE, FALSE, 1, 20.0000, 50, 'STOPPED',
     FALSE, TRUE, FALSE, FALSE, FALSE, FALSE,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64', 0),
    -- 5m SHORT → RUNNER_ONLY (via param override)
    ('7e570001-0000-0000-0000-000000000008', '76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2',
     '7e574e57-7e57-7e57-7e57-7e577e577e57', 'TEST', 'test_5m_short_runner_only',
     'BTCUSDT', '5m', TRUE, FALSE, TRUE, 1, 20.0000, 60, 'STOPPED',
     FALSE, TRUE, FALSE, FALSE, FALSE, FALSE,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64', 0)
ON CONFLICT (account_strategy_id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. strategy_param presets (one active preset per account_strategy)
--    Rows 7 and 8 (5m LONG/SHORT) carry the RUNNER_ONLY override; the rest
--    use empty overrides so resolveExitStructure() falls back to the
--    interval-based default.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO strategy_param (
    param_id, account_strategy_id, name, param_overrides,
    is_active, is_deleted, version,
    created_time, created_by, updated_time, updated_by
)
VALUES
    ('7e570002-0000-0000-0000-000000000001', '7e570001-0000-0000-0000-000000000001',
     'test-default', '{}'::jsonb, TRUE, FALSE, 0,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64'),
    ('7e570002-0000-0000-0000-000000000002', '7e570001-0000-0000-0000-000000000002',
     'test-default', '{}'::jsonb, TRUE, FALSE, 0,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64'),
    ('7e570002-0000-0000-0000-000000000003', '7e570001-0000-0000-0000-000000000003',
     'test-default', '{}'::jsonb, TRUE, FALSE, 0,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64'),
    ('7e570002-0000-0000-0000-000000000004', '7e570001-0000-0000-0000-000000000004',
     'test-default', '{}'::jsonb, TRUE, FALSE, 0,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64'),
    ('7e570002-0000-0000-0000-000000000005', '7e570001-0000-0000-0000-000000000005',
     'test-default', '{}'::jsonb, TRUE, FALSE, 0,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64'),
    ('7e570002-0000-0000-0000-000000000006', '7e570001-0000-0000-0000-000000000006',
     'test-default', '{}'::jsonb, TRUE, FALSE, 0,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64'),
    ('7e570002-0000-0000-0000-000000000007', '7e570001-0000-0000-0000-000000000007',
     'test-runner-only', '{"exitStructure": "RUNNER_ONLY"}'::jsonb, TRUE, FALSE, 0,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64'),
    ('7e570002-0000-0000-0000-000000000008', '7e570001-0000-0000-0000-000000000008',
     'test-runner-only', '{"exitStructure": "RUNNER_ONLY"}'::jsonb, TRUE, FALSE, 0,
     NOW(), 'flyway:V64', NOW(), 'flyway:V64')
ON CONFLICT (param_id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. strategy_promotion_log — RESEARCH → PAPER_TRADE for definition + each
--    account_strategy. Without these, StrategyPromotionService.currentState()
--    returns RESEARCH regardless of flag values, and the /research dashboard
--    shows the strategy as un-promoted.
--    chk_promotion_log_scope: exactly one of (account_strategy_id,
--    strategy_definition_id) must be set per row.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO strategy_promotion_log (
    promotion_id, account_strategy_id, strategy_definition_id,
    strategy_code, from_state, to_state,
    reviewer_user_id, reason, evidence, created_time
)
SELECT * FROM (VALUES
    -- Definition-scope: account_strategy_id=NULL, strategy_definition_id set.
    ('7e570003-0000-0000-0000-000000000000'::uuid, NULL::uuid,
     '7e574e57-7e57-7e57-7e57-7e577e577e57'::uuid,
     'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid,
     'V64 seed: wire TEST definition into paper-trade pipeline for end-to-end execution validation.',
     NULL::jsonb, NOW()),
    -- Account-scope: strategy_definition_id=NULL, account_strategy_id set.
    ('7e570003-0000-0000-0000-000000000001'::uuid, '7e570001-0000-0000-0000-000000000001'::uuid,
     NULL::uuid, 'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid, 'V64 seed: TEST 15m LONG → SINGLE.', NULL::jsonb, NOW()),
    ('7e570003-0000-0000-0000-000000000002'::uuid, '7e570001-0000-0000-0000-000000000002'::uuid,
     NULL::uuid, 'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid, 'V64 seed: TEST 15m SHORT → SINGLE.', NULL::jsonb, NOW()),
    ('7e570003-0000-0000-0000-000000000003'::uuid, '7e570001-0000-0000-0000-000000000003'::uuid,
     NULL::uuid, 'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid, 'V64 seed: TEST 1h LONG → TP1_RUNNER.', NULL::jsonb, NOW()),
    ('7e570003-0000-0000-0000-000000000004'::uuid, '7e570001-0000-0000-0000-000000000004'::uuid,
     NULL::uuid, 'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid, 'V64 seed: TEST 1h SHORT → TP1_RUNNER.', NULL::jsonb, NOW()),
    ('7e570003-0000-0000-0000-000000000005'::uuid, '7e570001-0000-0000-0000-000000000005'::uuid,
     NULL::uuid, 'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid, 'V64 seed: TEST 4h LONG → TP1_TP2_RUNNER.', NULL::jsonb, NOW()),
    ('7e570003-0000-0000-0000-000000000006'::uuid, '7e570001-0000-0000-0000-000000000006'::uuid,
     NULL::uuid, 'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid, 'V64 seed: TEST 4h SHORT → TP1_TP2_RUNNER.', NULL::jsonb, NOW()),
    ('7e570003-0000-0000-0000-000000000007'::uuid, '7e570001-0000-0000-0000-000000000007'::uuid,
     NULL::uuid, 'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid, 'V64 seed: TEST 5m LONG → RUNNER_ONLY (preset override).', NULL::jsonb, NOW()),
    ('7e570003-0000-0000-0000-000000000008'::uuid, '7e570001-0000-0000-0000-000000000008'::uuid,
     NULL::uuid, 'TEST', 'RESEARCH', 'PAPER_TRADE',
     NULL::uuid, 'V64 seed: TEST 5m SHORT → RUNNER_ONLY (preset override).', NULL::jsonb, NOW())
) AS rows(promotion_id, account_strategy_id, strategy_definition_id, strategy_code,
         from_state, to_state, reviewer_user_id, reason, evidence, created_time)
ON CONFLICT (promotion_id) DO NOTHING;
