-- Vol-targeted book sizing toggle + target.
--
-- When enabled, BookVolTargetingService scales every entry's size such
-- that:
--   (a) realized strategy volatility (from last 30d daily-bucketed P&L)
--       hits the per-strategy vol target, and
--   (b) when other strategies on the same account have open same-side
--       positions, a concurrency haircut shrinks the new entry so the
--       book doesn't double up correlated bets.
--
-- Disabled by default — opt-in. Existing strategies keep their legacy
-- riskAmount × balance sizing path until the user flips the toggle.
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS vol_targeting_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS book_vol_target_pct NUMERIC(5, 2) NOT NULL DEFAULT 15.00;
