-- V62 — Toggleable risk gates (kill-switch, correlation, concurrent-cap).
--
-- Why:
--   Pre-V62 split: live ran kill-switch + correlation + account-level
--   concurrent-cap unconditionally through RiskGuardService; backtest ran
--   none of them. Same input could produce different outcomes between the
--   two paths — see Phase A parity audit findings 1, 3, 7.
--
-- This migration:
--   1. Adds explicit boolean toggles for the three previously-unconditional
--      live-only gates, so each can be opted into or out of per strategy.
--      regime_gate_enabled already exists from V43; it joins the same shape.
--   2. Adds per-backtest-run override columns mirroring strategy_allow_long
--      (V58) — wizard can flip a single gate for one research run without
--      touching the persisted account_strategy row.
--
-- Backfill direction (deliberate, per user direction 2026-05-13):
--   All three new columns default to FALSE. Existing rows therefore start
--   with every newly-introduced gate disabled, matching how backtest has
--   always behaved. This is a controlled REGRESSION of live behaviour: a
--   strategy whose is_kill_switch_tripped becomes TRUE after this migration
--   will no longer be auto-blocked from new entries until an operator flips
--   kill_switch_gate_enabled to TRUE. Acceptable because:
--     - the platform is currently paper-trade only (simulated=true on every
--       active row, account holds ~$40 USDT);
--     - no kill-switch is currently tripped;
--     - the user wants live↔backtest parity by symmetric off-state first,
--       then opt back in once each gate is validated.
--
-- Resolver precedence (applies in both live and backtest after V62):
--   1. If backtest_run.strategy_<gate>_overrides[CODE] is present:
--        use the override (true/false).
--   2. Else: use account_strategy.<gate>_gate_enabled.
--   Live never has a backtest_run, so it always falls through to step 2.

ALTER TABLE account_strategy
    ADD COLUMN kill_switch_gate_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN correlation_gate_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN concurrent_cap_gate_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN account_strategy.kill_switch_gate_enabled IS
    'V62 — when TRUE, StrategyGateService blocks new entries whenever '
    'is_kill_switch_tripped is TRUE. When FALSE, the trip state is recorded '
    'but does not block entries. Default FALSE (backfilled). Pair with '
    'is_kill_switch_tripped + dd_kill_threshold_pct.';

COMMENT ON COLUMN account_strategy.correlation_gate_enabled IS
    'V62 — when TRUE, CorrelationGuardService caps correlated same-side '
    'stacking via account.max_corr_block_threshold + '
    'max_capital_concentration_pct. When FALSE, the guard is skipped '
    'entirely. Default FALSE (backfilled).';

COMMENT ON COLUMN account_strategy.concurrent_cap_gate_enabled IS
    'V62 — when TRUE, StrategyGateService enforces '
    'accounts.max_concurrent_longs / max_concurrent_shorts / '
    'max_concurrent_trades at entry time. When FALSE, the caps are not '
    'applied. Default FALSE (backfilled).';

ALTER TABLE backtest_run
    ADD COLUMN strategy_kill_switch_overrides    JSONB,
    ADD COLUMN strategy_regime_overrides         JSONB,
    ADD COLUMN strategy_correlation_overrides    JSONB,
    ADD COLUMN strategy_concurrent_cap_overrides JSONB;

COMMENT ON COLUMN backtest_run.strategy_kill_switch_overrides IS
    'V62 — per-strategy kill-switch gate override for this run. Map of '
    'strategy_code → boolean. Missing key falls back to '
    'account_strategy.kill_switch_gate_enabled. Mirrors strategy_allow_long.';

COMMENT ON COLUMN backtest_run.strategy_regime_overrides IS
    'V62 — per-strategy regime gate override for this run. Map of '
    'strategy_code → boolean. Missing key falls back to '
    'account_strategy.regime_gate_enabled (V43).';

COMMENT ON COLUMN backtest_run.strategy_correlation_overrides IS
    'V62 — per-strategy correlation gate override for this run. Map of '
    'strategy_code → boolean. Missing key falls back to '
    'account_strategy.correlation_gate_enabled.';

COMMENT ON COLUMN backtest_run.strategy_concurrent_cap_overrides IS
    'V62 — per-strategy concurrent-cap gate override for this run. Map of '
    'strategy_code → boolean. Missing key falls back to '
    'account_strategy.concurrent_cap_gate_enabled.';
