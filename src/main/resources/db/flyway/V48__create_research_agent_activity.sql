CREATE TABLE research_agent_activity (
    activity_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    session_id      UUID,
    agent_name      TEXT         NOT NULL,
    activity_type   TEXT         NOT NULL,
    strategy_code   TEXT,
    title           TEXT         NOT NULL,
    details         JSONB,
    related_id      UUID,
    related_type    TEXT,
    status          TEXT         NOT NULL DEFAULT 'SUCCESS',
    created_time    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      TEXT,
    updated_time    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by      TEXT,
    CONSTRAINT pk_research_agent_activity PRIMARY KEY (activity_id)
);

CREATE INDEX idx_raa_session_created  ON research_agent_activity (session_id, created_time);
CREATE INDEX idx_raa_type_created     ON research_agent_activity (activity_type, created_time DESC);
CREATE INDEX idx_raa_strategy_created ON research_agent_activity (strategy_code, created_time DESC)
    WHERE strategy_code IS NOT NULL;
CREATE INDEX idx_raa_created          ON research_agent_activity (created_time DESC);
CREATE INDEX idx_raa_agent_created    ON research_agent_activity (agent_name, created_time DESC);

GRANT SELECT, INSERT ON research_agent_activity TO blackheart_research;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_readonly') THEN
        EXECUTE 'GRANT SELECT ON research_agent_activity TO blackheart_readonly';
    END IF;
END$$;
