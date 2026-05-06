-- Append-only audit trail for security-sensitive mutations.
--
-- Records who (actor_user_id) did what (action) to which entity
-- (entity_type + entity_id), with the before/after snapshots stored as
-- JSONB for direct querying. The audit row commits in the same
-- transaction as the mutation it records — so a rolled-back mutation
-- rolls back its audit row too.
--
-- Idempotent: every statement uses IF NOT EXISTS.
CREATE TABLE IF NOT EXISTS audit_event (
    audit_event_id  UUID         PRIMARY KEY,
    actor_user_id   UUID,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(100),
    entity_id       UUID,
    before_data     JSONB,
    after_data      JSONB,
    reason          VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL
);

-- Common access patterns: latest events per actor, latest events per entity.
CREATE INDEX IF NOT EXISTS idx_audit_event_actor_created
    ON audit_event (actor_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_event_entity_created
    ON audit_event (entity_type, entity_id, created_at DESC);

-- Newest-first scans across the whole table for the admin "recent activity"
-- view. Plain DESC index — covers ORDER BY created_at DESC on its own.
CREATE INDEX IF NOT EXISTS idx_audit_event_created
    ON audit_event (created_at DESC);
