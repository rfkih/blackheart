ALTER TABLE account_strategy
    ADD COLUMN IF NOT EXISTS is_deleted boolean NOT NULL DEFAULT false;

ALTER TABLE account_strategy
    ADD COLUMN IF NOT EXISTS deleted_at timestamp NULL;

CREATE INDEX IF NOT EXISTS idx_account_strategy_active
    ON account_strategy (account_id)
    WHERE is_deleted = false;
