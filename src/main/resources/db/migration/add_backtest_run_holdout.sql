-- Locked-holdout protection on backtest_run.
--
-- A "holdout run" is the single-shot evaluation that runs *after* a sweep
-- has chosen its winner from the OOS leaderboard. It targets a window
-- segment that the sweep was forbidden from touching during optimization,
-- so its result is the unbiased estimate of the strategy's edge.
--
-- The discipline is enforced server-side: only ResearchSweepService is
-- allowed to insert rows with is_holdout_run=true, and only ONCE per sweep
-- (a unique partial index makes second attempts impossible at the DB level
-- even if a race condition slipped past the service-layer check).
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS is_holdout_run BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS holdout_for_sweep_id UUID;

-- One holdout per sweep, ever. Partial index because legacy rows have
-- is_holdout_run=false and we don't want them in the uniqueness scope.
CREATE UNIQUE INDEX IF NOT EXISTS idx_backtest_run_holdout_per_sweep
    ON backtest_run (holdout_for_sweep_id)
    WHERE is_holdout_run = TRUE;
