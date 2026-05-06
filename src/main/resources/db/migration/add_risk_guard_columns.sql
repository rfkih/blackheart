-- Risk-guard columns for the drawdown kill-switch + per-account
-- concurrency cap. Both gates run in RiskGuardService, called from
-- LiveTradingDecisionExecutorService before every OPEN_LONG / OPEN_SHORT.
--
-- The kill-switch is "sticky": once tripped, new entries are blocked for
-- the strategy until an admin explicitly re-arms it via the rearm
-- endpoint. This prevents the bleeding-doom-loop where the strategy
-- self-recovers by happenstance of a winning trade right after tripping.
ALTER TABLE account_strategy
    ADD COLUMN IF NOT EXISTS dd_kill_threshold_pct NUMERIC(5, 2) NOT NULL DEFAULT 25.00,
    ADD COLUMN IF NOT EXISTS is_kill_switch_tripped BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS kill_switch_tripped_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS kill_switch_reason TEXT;

-- Concurrent same-direction position cap. Default 2 long + 2 short across
-- every strategy on the account. Crude but effective: prevents a "all
-- strategies fire LONG on the same trigger candle" double-up that's the
-- single biggest concentration risk in a single-asset book.
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS max_concurrent_longs INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN IF NOT EXISTS max_concurrent_shorts INTEGER NOT NULL DEFAULT 2;
