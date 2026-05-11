-- V58 — Per-strategy direction overrides for backtest runs.
--
-- Why:
--   V58 (in code) made backtest direction flags per-strategy by reading
--   account_strategy.allow_long / allow_short for each bound AS. That gives
--   live↔backtest parity but removes the operator's ability to ad-hoc
--   research "what if LSR ran with shorts?" without permanently flipping
--   the live row. This migration adds the missing override layer.
--
-- Wire format:
--   strategy_allow_long  = {"LSR": true,  "VCB": false}
--   strategy_allow_short = {"LSR": true,  "VCB": false}
--
--   Map of strategy_code → boolean. Mirrors strategy_allocations /
--   strategy_risk_pcts. A null map / missing key falls back to the bound
--   account_strategy.allow_long / allow_short — preserving the V58
--   per-strategy default. An explicit `false` forces direction off for
--   that strategy in this run, even when the persisted row allows it.
--
-- Resolver precedence per strategy:
--   1. backtest_run.strategy_allow_{long,short}[CODE]   (wizard override)
--   2. account_strategy.allow_{long,short}              (bound AS default)
--   3. backtest_run.allow_{long,short}                  (run-level fallback,
--                                                        used for ad-hoc
--                                                        spec strategies
--                                                        that don't pin
--                                                        an account_strategy)
--
-- Rollout safety:
--   Both columns are nullable JSONB — old runs replay through the existing
--   per-strategy default path. New wizard runs only populate the maps when
--   the operator explicitly toggles a flag away from the persisted value.

ALTER TABLE backtest_run
    ADD COLUMN strategy_allow_long  JSONB,
    ADD COLUMN strategy_allow_short JSONB;

COMMENT ON COLUMN backtest_run.strategy_allow_long IS
    'Per-strategy allowLong override for this run. Map of strategy_code → '
    'boolean. Null or missing key falls back to account_strategy.allow_long. V58+.';

COMMENT ON COLUMN backtest_run.strategy_allow_short IS
    'Per-strategy allowShort override for this run. Map of strategy_code → '
    'boolean. Null or missing key falls back to account_strategy.allow_short. V58+.';
