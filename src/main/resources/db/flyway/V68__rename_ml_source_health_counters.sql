-- V68 — Rename ml_source_health.*_24h counters to *_total.
--
-- Why:
--   V67 named these columns `rows_inserted_24h`, `errors_24h`,
--   `rejected_pit_violations_24h` — implying a 24-hour rolling window.
--   The actual implementation in `update_source_health` increments them
--   unboundedly on each pull, so after a week of live ingestion the values
--   represent a week of activity, not 24 hours. The naming was a lie.
--
--   Two ways to resolve:
--     a) Implement a true 24-hour rolling window (timestamped event log
--        + periodic decay, or query the source raw tables for `now() - 24h`).
--        Real work for a feature nobody is depending on yet.
--     b) Rename to `*_total` so the column matches what the value
--        actually is. Honest about lifetime semantics; can add separate
--        24h columns later if a dashboard needs them.
--
--   Picking (b). Aligns the column name with reality. Frontend label
--   should read "rows since first pull" or "rows since reset".
--
-- Safe-to-apply state:
--   ml_source_health currently has 7 rows (one per source) seeded by V67
--   with all three counters at 0. No real ingestion has happened yet
--   (Phase 1 M2 is mid-rollout), so the rename loses no information.

ALTER TABLE ml_source_health
    RENAME COLUMN rows_inserted_24h TO rows_inserted_total;

ALTER TABLE ml_source_health
    RENAME COLUMN errors_24h TO errors_total;

ALTER TABLE ml_source_health
    RENAME COLUMN rejected_pit_violations_24h TO rejected_pit_violations_total;

COMMENT ON COLUMN ml_source_health.rows_inserted_total IS
    'V68 (renamed from rows_inserted_24h) — Cumulative rows written by '
    'blackheart-ingest live + heavy workers. Resets only via manual SQL. '
    'A future migration may add `rows_inserted_24h` as a separate true '
    'rolling-window column if the dashboard needs it.';

COMMENT ON COLUMN ml_source_health.errors_total IS
    'V68 (renamed from errors_24h) — Cumulative fetch failures since first '
    'pull. Resets only via manual SQL.';

COMMENT ON COLUMN ml_source_health.rejected_pit_violations_total IS
    'V68 (renamed from rejected_pit_violations_24h) — Cumulative rows '
    'rejected by PIT guards (future event_time, excessive backfill lag). '
    'Resets only via manual SQL. Non-zero is a signal the source''s '
    'timestamps are drifting — investigate before trusting the data.';
