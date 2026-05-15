-- V51 attempted to drop the legacy global UNIQUE on accounts.username, but
-- targeted the wrong constraint name (`uk_accounts_username`). The actual
-- name from V1 baseline is `uq_accounts_username`. The drop silently
-- succeeded ("IF EXISTS") leaving the constraint in place, so per-user
-- uniqueness wasn't actually enforceable - the global unique kept blocking.
--
-- This migration removes the right constraint. Per-user uniqueness is
-- enforced by `uq_accounts_user_username_lower` from V51.
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS uq_accounts_username;
DROP INDEX IF EXISTS uq_accounts_username;
