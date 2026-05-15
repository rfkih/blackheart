-- V75 — Widen model_registry.status to fit its CHECK-constraint values.
--
-- V66 declared status VARCHAR(20) with a CHECK constraint listing values
-- including 'awaiting_operator_review' (24 chars) and 'rejected_by_operator'
-- (20 chars exactly). Inserting 'awaiting_operator_review' fails with
-- StringDataRightTruncationError (22001) — the value matches the CHECK
-- but doesn't fit the column type. Caught by M5e (POST /models/register)
-- on the first real registration of a passing-gauntlet model that wasn't
-- deployment-ready.
--
-- VARCHAR(40) is the same width used by family/purpose in the same table;
-- aligning on it costs nothing and leaves room for a future status value
-- without another migration.

ALTER TABLE model_registry
    ALTER COLUMN status TYPE VARCHAR(40);
