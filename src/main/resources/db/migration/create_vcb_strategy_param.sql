CREATE TABLE IF NOT EXISTS vcb_strategy_param (
    account_strategy_id UUID NOT NULL,
    param_overrides     JSONB NOT NULL DEFAULT '{}',
    version             BIGINT NOT NULL DEFAULT 0,
    created_time        TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(150),
    updated_time        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(150),

    CONSTRAINT pk_vcb_strategy_param PRIMARY KEY (account_strategy_id),
    CONSTRAINT fk_vcb_param_account_strategy
        FOREIGN KEY (account_strategy_id)
        REFERENCES account_strategy (account_strategy_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vcb_param_overrides_gin
    ON vcb_strategy_param USING GIN (param_overrides);

CREATE OR REPLACE FUNCTION update_vcb_param_updated_time()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_time = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_vcb_param_updated_time ON vcb_strategy_param;
CREATE TRIGGER trg_vcb_param_updated_time
    BEFORE UPDATE ON vcb_strategy_param
    FOR EACH ROW
    EXECUTE FUNCTION update_vcb_param_updated_time();
