-- Optimistic-locking version counter for account_strategy.
-- Prevents concurrent activation requests from both succeeding and
-- breaking the "at most one enabled preset per (account, strategy, symbol,
-- interval)" invariant. JPA @Version increments this on every UPDATE;
-- a stale-read conflict throws OptimisticLockingFailureException.
ALTER TABLE account_strategy
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
