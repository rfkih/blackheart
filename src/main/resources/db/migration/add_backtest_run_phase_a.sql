-- Phase A — multi-strategy backtest controls.
--
-- max_concurrent_strategies caps how many trades can be open at once across
-- all strategies in a single run; null = no cap (legacy behavior).
-- strategy_allocations is an optional per-run override of each strategy's
-- capital allocation; key = strategy code, value = allocation % (0–100).
-- Strategies not present in the map fall back to
-- account_strategy.capital_allocation_pct. Persisted on the run row so the
-- "Re-run with these params" flow can faithfully replay.
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS max_concurrent_strategies INTEGER,
    ADD COLUMN IF NOT EXISTS strategy_allocations      JSONB;
