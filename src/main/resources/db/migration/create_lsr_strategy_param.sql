-- =============================================================================
-- LSR Strategy per-account-strategy parameter overrides
-- =============================================================================
-- Only account strategies that deviate from defaults have a row here.
-- A missing row means "use all defaults".
-- The param_overrides JSONB column stores only the overridden fields (partial map).
-- =============================================================================

CREATE TABLE IF NOT EXISTS lsr_strategy_param (
    account_strategy_id UUID NOT NULL,
    param_overrides     JSONB NOT NULL DEFAULT '{}',
    version             BIGINT NOT NULL DEFAULT 0,
    created_time        TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(150),
    updated_time        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(150),

    CONSTRAINT pk_lsr_strategy_param PRIMARY KEY (account_strategy_id),
    CONSTRAINT fk_lsr_param_account_strategy
        FOREIGN KEY (account_strategy_id)
        REFERENCES account_strategy (account_strategy_id)
        ON DELETE CASCADE
);

-- GIN index for JSONB field-level querying (e.g. WHERE param_overrides ? 'adxTrendingMin')
CREATE INDEX IF NOT EXISTS idx_lsr_param_overrides_gin
    ON lsr_strategy_param USING GIN (param_overrides);

-- Update trigger: keep updated_time current on every row change
CREATE OR REPLACE FUNCTION update_lsr_param_updated_time()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_time = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_lsr_param_updated_time ON lsr_strategy_param;
CREATE TRIGGER trg_lsr_param_updated_time
    BEFORE UPDATE ON lsr_strategy_param
    FOR EACH ROW
    EXECUTE FUNCTION update_lsr_param_updated_time();

COMMENT ON TABLE lsr_strategy_param IS
    'Per-account-strategy LSR parameter overrides. '
    'Missing row = use strategy defaults. '
    'param_overrides stores only the fields the user has explicitly changed.';

COMMENT ON COLUMN lsr_strategy_param.param_overrides IS
    'JSONB map of camelCase param names to override values. '
    'E.g.: {"adxTrendingMin": 25, "maxRiskPct": 0.02}';

COMMENT ON COLUMN lsr_strategy_param.version IS
    'Optimistic-locking version. Incremented by Hibernate on each UPDATE.';
