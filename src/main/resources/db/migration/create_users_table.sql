-- =============================================================================
-- create_users_table.sql
-- Creates the platform user table and links it to the existing accounts table.
--
-- Run BEFORE starting the application for the first time with User entity
-- support. Hibernate ddl-auto=update will handle the users table columns
-- automatically, but the FK constraint and index on accounts.user_id must be
-- added explicitly since Hibernate cannot rename columns.
--
-- After running this script, seed at least one ADMIN user so that the legacy
-- HTTP Basic endpoints remain accessible. See seed_admin_user.sql.
-- =============================================================================

BEGIN;

-- =============================================================================
-- 1. Users table
-- =============================================================================
CREATE TABLE IF NOT EXISTS users (
    user_id        UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    full_name      VARCHAR(255) NOT NULL,
    phone_number   VARCHAR(30),
    role           VARCHAR(30)  NOT NULL DEFAULT 'USER',
    status         VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    email_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at  TIMESTAMP,

    -- Audit trail (BaseEntity)
    created_time   TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(150),
    updated_time   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by     VARCHAR(150),

    CONSTRAINT uk_users_email UNIQUE (email)
);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);
CREATE INDEX IF NOT EXISTS idx_users_email     ON users (email);

-- =============================================================================
-- 2. Link accounts → users
-- =============================================================================
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS user_id UUID;

-- Referential integrity: an account must belong to a known user (nullable allows
-- pre-existing rows to remain until they are migrated).
ALTER TABLE accounts
    DROP CONSTRAINT IF EXISTS fk_accounts_user_id;

ALTER TABLE accounts
    ADD CONSTRAINT fk_accounts_user_id
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_accounts_user_id ON accounts (user_id);

COMMIT;
