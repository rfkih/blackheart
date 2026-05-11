-- V59 — Partial index for the per-user in-flight backtest count query used by
-- BacktestConcurrencyGate. Without this index, every backtest submission does
-- a sequential scan of backtest_run filtered by status; with it the count is
-- sub-millisecond even as the table grows into the millions.
--
-- Covers: SELECT COUNT(*) FROM backtest_run WHERE user_id = ? AND status IN ('PENDING','RUNNING')

CREATE INDEX IF NOT EXISTS idx_backtest_run_active_per_user
    ON backtest_run (user_id)
    WHERE status IN ('PENDING', 'RUNNING');
