-- Reproducibility manifest on backtest_run.
--
-- Together with the existing config_snapshot (user param overrides), asset,
-- interval, and time-window columns these two fields are sufficient to
-- replay a run months later: the SHA pins the baked-in defaults + strategy
-- code, the version is human-readable for changelog cross-reference.
--
-- Both nullable: legacy rows pre-dating this migration have no manifest,
-- and runs submitted by callers without a known git context (local dev
-- without `git rev-parse`) record the literal string "unknown" rather than
-- failing.
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS git_commit_sha VARCHAR(40),
    ADD COLUMN IF NOT EXISTS app_version VARCHAR(50);
