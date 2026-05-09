-- BacktestRun.userId is referenced by the entity (see SECURITY_FIXES.md, finding 2.3
-- "Backtest IDOR") but was never added by any prior migration. This adds the
-- column + index. Nullable to preserve legacy rows; new rows get populated
-- from the authenticated JWT at submit time.
ALTER TABLE backtest_run ADD COLUMN IF NOT EXISTS user_id UUID;
CREATE INDEX IF NOT EXISTS idx_backtest_run_user_id ON backtest_run (user_id);
