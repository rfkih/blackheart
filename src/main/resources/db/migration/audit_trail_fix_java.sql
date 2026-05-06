-- =============================================================================
-- audit_trail_fix_java.sql
-- Standardise audit-trail columns on Java/Hibernate-managed tables to:
--   created_time  TIMESTAMP NOT NULL  (set once on INSERT)
--   created_by    VARCHAR(150) NULL
--   updated_time  TIMESTAMP NOT NULL  (refreshed on every UPDATE)
--   updated_by    VARCHAR(150) NULL
--
-- Run this script BEFORE deploying the updated Spring Boot application so that
-- Hibernate (ddl-auto=update) finds the columns already in place.
--
-- Excluded tables (Python-managed, handled by Alembic):
--   market_data, feature_store
-- =============================================================================

BEGIN;

-- =============================================================================
-- Group 1: backtest_run, backtest_trade, backtest_trade_position
-- Already have created_time / updated_time — only add created_by / updated_by
-- =============================================================================

ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE backtest_trade
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE backtest_trade_position
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

-- =============================================================================
-- Group 2: accounts, trades, trade_positions, portfolio, account_strategy,
--          strategy_definition, trend_following_config_detail,
--          training_jobs, scheduler_jobs, strategy_daily_realized_curve
-- Had created_at / updated_at → rename + add created_by / updated_by
-- =============================================================================

ALTER TABLE accounts
    RENAME COLUMN created_at TO created_time;
ALTER TABLE accounts
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE trades
    RENAME COLUMN created_at TO created_time;
ALTER TABLE trades
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE trades
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE trade_positions
    RENAME COLUMN created_at TO created_time;
ALTER TABLE trade_positions
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE trade_positions
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE portfolio
    RENAME COLUMN created_at TO created_time;
ALTER TABLE portfolio
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE portfolio
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE account_strategy
    RENAME COLUMN created_at TO created_time;
ALTER TABLE account_strategy
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE account_strategy
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE strategy_definition
    RENAME COLUMN created_at TO created_time;
ALTER TABLE strategy_definition
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE strategy_definition
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE trend_following_config_detail
    RENAME COLUMN created_at TO created_time;
ALTER TABLE trend_following_config_detail
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE trend_following_config_detail
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE training_jobs
    RENAME COLUMN created_at TO created_time;
ALTER TABLE training_jobs
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE training_jobs
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE scheduler_jobs
    RENAME COLUMN created_at TO created_time;
ALTER TABLE scheduler_jobs
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE scheduler_jobs
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

ALTER TABLE strategy_daily_realized_curve
    RENAME COLUMN created_at TO created_time;
ALTER TABLE strategy_daily_realized_curve
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE strategy_daily_realized_curve
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(150);

-- =============================================================================
-- Group 3: strategy_config
-- Had created_at / updated_at + already had created_by / updated_by (length 100)
-- Rename timestamps; widen created_by/updated_by to 150 to match standard
-- =============================================================================

ALTER TABLE strategy_config
    RENAME COLUMN created_at TO created_time;
ALTER TABLE strategy_config
    RENAME COLUMN updated_at TO updated_time;
ALTER TABLE strategy_config
    ALTER COLUMN created_by TYPE VARCHAR(150),
    ALTER COLUMN updated_by TYPE VARCHAR(150);

-- =============================================================================
-- Group 4: backtest_run_strategy, backtest_equity_point
-- Had only created_at — rename + add created_by, updated_time, updated_by
-- =============================================================================

ALTER TABLE backtest_run_strategy
    RENAME COLUMN created_at TO created_time;
ALTER TABLE backtest_run_strategy
    ADD COLUMN IF NOT EXISTS created_by   VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_time TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(150);

ALTER TABLE backtest_equity_point
    RENAME COLUMN created_at TO created_time;
ALTER TABLE backtest_equity_point
    ADD COLUMN IF NOT EXISTS created_by   VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_time TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(150);

-- =============================================================================
-- Group 5: backtest_trade_event, monte_carlo_run
-- Already had created_time — add created_by, updated_time, updated_by
-- =============================================================================

ALTER TABLE backtest_trade_event
    ADD COLUMN IF NOT EXISTS created_by   VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_time TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(150);

ALTER TABLE monte_carlo_run
    ADD COLUMN IF NOT EXISTS created_by   VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_time TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(150);

-- =============================================================================
-- Group 6: trade_execution_log, server_ip_log
-- Had no audit columns at all — add all four
-- (domain timestamps executedAt / recordedAt are kept untouched)
-- =============================================================================

ALTER TABLE trade_execution_log
    ADD COLUMN IF NOT EXISTS created_time TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS created_by   VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_time TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(150);

ALTER TABLE server_ip_log
    ADD COLUMN IF NOT EXISTS created_time TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS created_by   VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_time TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(150);

COMMIT;
