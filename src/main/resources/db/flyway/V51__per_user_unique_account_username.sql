-- Move accounts.username uniqueness from global to per-user.
--
-- The pre-existing constraint was a global unique (uk_accounts_username),
-- which (a) leaks account label existence across tenants - any logged-in user
-- could enumerate other users' labels by trying to create one with the same
-- name and reading the 409 error - and (b) makes the platform single-tenant
-- in practice (only one user could ever have a "Main" account).
--
-- This migration drops the global constraint and replaces it with a
-- composite (user_id, lower(username)) unique index that enforces per-user
-- uniqueness while allowing different users to pick the same label.

ALTER TABLE accounts DROP CONSTRAINT IF EXISTS uk_accounts_username;
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_username_key;
DROP INDEX IF EXISTS uk_accounts_username;

CREATE UNIQUE INDEX IF NOT EXISTS uq_accounts_user_username_lower
    ON accounts (user_id, LOWER(username));
