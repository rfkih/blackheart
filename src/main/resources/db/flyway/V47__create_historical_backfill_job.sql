-- Job tracking for the unified historical data integrity console.
-- Replaces the prior "synchronous endpoint that lies about success" pattern
-- (HistoricalDataService comment) with an async job model: the controller
-- inserts a PENDING row and returns immediately; an async worker flips
-- PENDING → RUNNING → SUCCESS/FAILED/CANCELLED with progress ticks the UI
-- can poll or subscribe to over STOMP.
--
-- All historical backfill operations route through this table — coverage
-- repair, NULL-column patches, range recomputes, funding history pulls.
-- One pair (symbol, interval) per job; the orchestrator service fans out
-- multiple pairs as separate rows so each remains independently cancellable
-- and observable.

CREATE TABLE IF NOT EXISTS historical_backfill_job (
    job_id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type            VARCHAR(80)  NOT NULL,
    symbol              VARCHAR(20),
    interval            VARCHAR(10),
    params              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    phase               VARCHAR(120),
    progress_done       INTEGER      NOT NULL DEFAULT 0,
    progress_total      INTEGER      NOT NULL DEFAULT 0,
    result              JSONB,
    error_message       TEXT,
    error_class         VARCHAR(200),
    cancel_requested    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_user_id  UUID,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    CONSTRAINT chk_historical_backfill_job_status
        CHECK (status IN ('PENDING','RUNNING','SUCCESS','FAILED','CANCELLED'))
);

-- Recent-jobs list (admin UI shows newest first).
CREATE INDEX IF NOT EXISTS idx_historical_backfill_job_created
    ON historical_backfill_job (created_at DESC);

-- Filter by status (e.g. operator wants to see all FAILED jobs from the last week).
CREATE INDEX IF NOT EXISTS idx_historical_backfill_job_status
    ON historical_backfill_job (status, created_at DESC);

-- Filter by (symbol, interval) — useful when triaging a specific pair.
CREATE INDEX IF NOT EXISTS idx_historical_backfill_job_pair
    ON historical_backfill_job (symbol, interval, created_at DESC)
    WHERE symbol IS NOT NULL;

-- Hot-path partial index for "any active job?" checks the orchestrator runs
-- to avoid double-submitting the same backfill. Bounded set, fast scan.
CREATE INDEX IF NOT EXISTS idx_historical_backfill_job_active
    ON historical_backfill_job (status)
    WHERE status IN ('PENDING','RUNNING');

COMMENT ON TABLE historical_backfill_job IS
    'Async job registry for the historical data integrity console. PENDING → RUNNING → SUCCESS/FAILED/CANCELLED. One pair per job.';
COMMENT ON COLUMN historical_backfill_job.job_type IS
    'Discriminator matched against registered HistoricalJobHandler beans (e.g. PATCH_NULL_COLUMN, RECOMPUTE_RANGE, BACKFILL_FUNDING_HISTORY).';
COMMENT ON COLUMN historical_backfill_job.params IS
    'Job-type-specific parameters as a freeform JSON document. Each handler validates the shape it expects.';
COMMENT ON COLUMN historical_backfill_job.phase IS
    'Coarse-grained progress label (e.g. "market_data", "feature_store", "patch:slope_200"). Updated by the handler at phase boundaries.';
COMMENT ON COLUMN historical_backfill_job.cancel_requested IS
    'Cooperative cancellation flag — the handler polls this and exits early when set. Status flips to CANCELLED on early exit.';
