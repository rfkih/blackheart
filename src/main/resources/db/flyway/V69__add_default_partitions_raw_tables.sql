-- V69 — DEFAULT partitions for ML raw-data tables.
--
-- Why:
--   V66 created macro_raw / onchain_raw / news_raw / social_raw as
--   RANGE-partitioned on event_time with monthly partitions covering only
--   2024-12 .. 2027-12. Any backfill that pulls historical data older than
--   2024-12-01 (DefiLlama goes back to 2018, CoinMetrics community-tier to
--   2010, CoinGecko's free 365-day window can dip below 2024-12 depending
--   on dispatch date) fails with "no partition of relation found for row".
--   The whole INSERT batch then rolls back and the backfill silently
--   produces zero rows.
--
--   DEFAULT partitions catch any rows whose event_time falls outside the
--   declared monthly windows. Inserts no longer fail; queries still prune
--   the dated partitions normally — they just additionally scan the
--   (usually small) DEFAULT partition.
--
-- How to apply:
--   Flyway-managed. No data movement required. Operator may later promote
--   ranges of dated rows out of *_default into new monthly partitions when
--   deeper-history backfills become routine.

CREATE TABLE IF NOT EXISTS macro_raw_default
    PARTITION OF macro_raw DEFAULT;

CREATE TABLE IF NOT EXISTS onchain_raw_default
    PARTITION OF onchain_raw DEFAULT;

CREATE TABLE IF NOT EXISTS news_raw_default
    PARTITION OF news_raw DEFAULT;

CREATE TABLE IF NOT EXISTS social_raw_default
    PARTITION OF social_raw DEFAULT;

-- Role grants: mirror V66 grants on each partition so blackheart_trading
-- (writer) and blackheart_research (reader) inherit access. Wrapped in
-- pg_roles existence checks so the migration is a no-op in environments
-- where the roles haven't been provisioned (matches V66's pattern).
DO $$
DECLARE
    t TEXT;
    parts TEXT[] := ARRAY[
        'macro_raw_default',
        'onchain_raw_default',
        'news_raw_default',
        'social_raw_default'
    ];
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_trading') THEN
        FOREACH t IN ARRAY parts LOOP
            EXECUTE format('GRANT SELECT, INSERT, UPDATE ON %I TO blackheart_trading', t);
        END LOOP;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_research') THEN
        FOREACH t IN ARRAY parts LOOP
            EXECUTE format('GRANT SELECT ON %I TO blackheart_research', t);
        END LOOP;
    END IF;
END $$;

COMMENT ON TABLE macro_raw_default IS
    'V69 — DEFAULT partition catching event_time values outside the dated monthly partitions. Mostly populated during deep-history backfills before dedicated monthly partitions are added.';
COMMENT ON TABLE onchain_raw_default IS
    'V69 — DEFAULT partition (see macro_raw_default for rationale).';
COMMENT ON TABLE news_raw_default IS
    'V69 — DEFAULT partition (see macro_raw_default for rationale).';
COMMENT ON TABLE social_raw_default IS
    'V69 — DEFAULT partition (see macro_raw_default for rationale).';
