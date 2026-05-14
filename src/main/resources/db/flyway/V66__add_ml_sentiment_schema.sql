-- V66 — ML/sentiment integration schema.
--
-- Why:
--   Phase 1 / M1 of the ML/sentiment blueprint
--   (see memory: project_ml_blueprint.md). Establishes the data, registry,
--   model, signal, and audit planes required for HYBRID modulation of the
--   existing LSR/VCB/VBO strategies and a new INDEPENDENT-mode
--   ML_DIRECTIONAL strategy. BTC-first; symbol-keyed throughout so ETH and
--   other symbols are config additions, not migrations.
--
-- Scope (18 new tables, 2 ALTERs, 1 strategy_definition seed):
--   Group A — Raw data tables (4, monthly-partitioned by event_time):
--     macro_raw, onchain_raw, news_raw, social_raw
--   Group B — Registry tables (2):
--     feature_registry, feature_compute_run
--   Group C — Model layer (2):
--     model_registry, training_run
--   Group D — Signal layer (3):
--     signal_definition, signal_history (partitioned by ts), signal_health
--   Group E — Research workflow (2):
--     research_runs, reviews
--   Group F — Promotion & audit (5):
--     model_promotion_gauntlet,
--     order_audit (partitioned by decided_at),
--     ml_kill_switch_audit, reviewer_audit, researcher_override_budget
--   Group G — ALTERs:
--     strategy_definition: ml_mode, ml_mode_shadow
--     account_strategy:    per_symbol_exposure_cap_pct
--   Group H — Seed: ML_DIRECTIONAL row in strategy_definition.
--   Group I — Role grants/revokes for blackheart_trading / blackheart_research.
--
-- BaseEntity discipline (operator directive, 2026-05-14):
--   Every new table carries the four standard audit columns
--     created_time (CreationTimestamp), created_by,
--     updated_time (UpdateTimestamp), updated_by.
--   For partitioned tables the columns live on the parent and inherit to all
--   children. CLAUDE.md previously exempted market_data + feature_store;
--   the operator explicitly overrode that for this migration so the agent's
--   provenance is uniform across the ML plane.
--
-- Partitioning strategy:
--   Raw + signal_history tables partition monthly on the primary time
--   axis. Initial partitions cover 2024-12 through 2027-12 (covers 17-mo
--   backfill window from Phase 1 M2 plus ~19 mo forward). order_audit
--   covers 2026-05 through 2027-12 — only forward since it's populated
--   from Phase 4 M7. New partitions are added by a cron job documented in
--   `monitor.py` (Phase 1 M2 deliverable).
--
-- Idempotency: every CREATE uses IF NOT EXISTS / IF EXISTS. Rerun is a
--   no-op once applied.

-- ────────────────────────────────────────────────────────────────────────
-- GROUP A — Raw data tables (partitioned by event_time, monthly)
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS macro_raw (
    ingestion_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    source           VARCHAR(80)  NOT NULL,
    source_uri       TEXT         NOT NULL,
    symbol           VARCHAR(20),
    series_id        VARCHAR(120) NOT NULL,
    event_time       TIMESTAMPTZ  NOT NULL,
    ingestion_time   TIMESTAMPTZ  NOT NULL,
    value            NUMERIC(28, 10),
    value_text       TEXT,
    content_hash     VARCHAR(64)  NOT NULL,
    schema_version   INT          NOT NULL DEFAULT 1,
    created_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(150),
    updated_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(150),
    PRIMARY KEY (ingestion_id, event_time)
) PARTITION BY RANGE (event_time);

CREATE UNIQUE INDEX IF NOT EXISTS uq_macro_raw_source_uri
    ON macro_raw (source, source_uri, event_time);
CREATE INDEX IF NOT EXISTS idx_macro_raw_series_time
    ON macro_raw (series_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_macro_raw_ingestion_time
    ON macro_raw (ingestion_time DESC);

CREATE TABLE IF NOT EXISTS onchain_raw (
    ingestion_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    source           VARCHAR(80)  NOT NULL,
    source_uri       TEXT         NOT NULL,
    symbol           VARCHAR(20),
    series_id        VARCHAR(120) NOT NULL,
    event_time       TIMESTAMPTZ  NOT NULL,
    ingestion_time   TIMESTAMPTZ  NOT NULL,
    value            NUMERIC(28, 10),
    value_text       TEXT,
    content_hash     VARCHAR(64)  NOT NULL,
    schema_version   INT          NOT NULL DEFAULT 1,
    created_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(150),
    updated_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(150),
    PRIMARY KEY (ingestion_id, event_time)
) PARTITION BY RANGE (event_time);

CREATE UNIQUE INDEX IF NOT EXISTS uq_onchain_raw_source_uri
    ON onchain_raw (source, source_uri, event_time);
CREATE INDEX IF NOT EXISTS idx_onchain_raw_series_time
    ON onchain_raw (series_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_onchain_raw_ingestion_time
    ON onchain_raw (ingestion_time DESC);

CREATE TABLE IF NOT EXISTS news_raw (
    ingestion_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    source           VARCHAR(80)  NOT NULL,
    source_uri       TEXT         NOT NULL,
    symbols          TEXT[],
    headline         TEXT,
    body_uri         TEXT,
    language         VARCHAR(10),
    event_time       TIMESTAMPTZ  NOT NULL,
    ingestion_time   TIMESTAMPTZ  NOT NULL,
    sentiment_score  NUMERIC(8, 6),
    content_hash     VARCHAR(64)  NOT NULL,
    schema_version   INT          NOT NULL DEFAULT 1,
    created_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(150),
    updated_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(150),
    PRIMARY KEY (ingestion_id, event_time)
) PARTITION BY RANGE (event_time);

CREATE UNIQUE INDEX IF NOT EXISTS uq_news_raw_source_uri
    ON news_raw (source, source_uri, event_time);
CREATE INDEX IF NOT EXISTS idx_news_raw_event_time
    ON news_raw (event_time DESC);
CREATE INDEX IF NOT EXISTS idx_news_raw_symbols
    ON news_raw USING GIN (symbols);

CREATE TABLE IF NOT EXISTS social_raw (
    ingestion_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    source           VARCHAR(80)  NOT NULL,
    source_uri       TEXT         NOT NULL,
    symbol           VARCHAR(20),
    series_id        VARCHAR(120),
    author_hash      VARCHAR(64),
    content_text     TEXT,
    language         VARCHAR(10),
    event_time       TIMESTAMPTZ  NOT NULL,
    ingestion_time   TIMESTAMPTZ  NOT NULL,
    score            NUMERIC(28, 10),
    content_hash     VARCHAR(64)  NOT NULL,
    schema_version   INT          NOT NULL DEFAULT 1,
    created_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(150),
    updated_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       VARCHAR(150),
    PRIMARY KEY (ingestion_id, event_time)
) PARTITION BY RANGE (event_time);

CREATE UNIQUE INDEX IF NOT EXISTS uq_social_raw_source_uri
    ON social_raw (source, source_uri, event_time);
CREATE INDEX IF NOT EXISTS idx_social_raw_event_time
    ON social_raw (event_time DESC);

-- Generate monthly partitions for raw tables covering 2024-12 .. 2027-12.
DO $$
DECLARE
    raw_tables TEXT[] := ARRAY['macro_raw', 'onchain_raw', 'news_raw', 'social_raw'];
    t TEXT;
    d DATE := DATE '2024-12-01';
    end_d DATE := DATE '2028-01-01';
    next_d DATE;
    part_name TEXT;
BEGIN
    FOREACH t IN ARRAY raw_tables LOOP
        d := DATE '2024-12-01';
        WHILE d < end_d LOOP
            next_d := d + INTERVAL '1 month';
            part_name := t || '_' || TO_CHAR(d, 'YYYY_MM');
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
                'FOR VALUES FROM (%L) TO (%L);',
                part_name, t, d, next_d
            );
            d := next_d;
        END LOOP;
    END LOOP;
END $$;

COMMENT ON TABLE macro_raw IS
    'V66 — Macro feed: FRED/ALFRED, CoinGecko, Binance funding+OI+L/S, alternative.me F&G. Partitioned monthly on event_time. event_time=publisher timestamp; ingestion_time=our server clock. Reject inserts where ingestion_time-event_time > source-specific backfill threshold.';
COMMENT ON COLUMN macro_raw.series_id IS
    'Source-internal series identifier, e.g. ''DXY'', ''DGS10'', ''btc_funding_8h'', ''fear_greed''. Joined to feature_registry.inputs.';
COMMENT ON COLUMN macro_raw.event_time IS
    'Publisher timestamp. PIT-authoritative time used by all downstream joins; never overwritten.';
COMMENT ON COLUMN macro_raw.ingestion_time IS
    'Our server clock at INSERT. Used to detect suspicious backfill (large gap to event_time → flag).';

COMMENT ON TABLE onchain_raw IS
    'V66 — On-chain + flow feeds: DefiLlama stablecoin supply, CoinMetrics community netflow/active-addresses. Partitioned monthly on event_time.';
COMMENT ON TABLE news_raw IS
    'V66 — News articles (deferred to V2 / Phase 7a). Empty in v1. body_uri points to blob storage; Postgres holds metadata only.';
COMMENT ON TABLE social_raw IS
    'V66 — Social posts (Reddit, etc., deferred to V2). Empty in v1.';

-- ────────────────────────────────────────────────────────────────────────
-- GROUP B — Registry tables
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS feature_registry (
    feature_name        VARCHAR(120) NOT NULL,
    version             INT          NOT NULL,
    family              VARCHAR(40)  NOT NULL,
    owner               VARCHAR(120),
    transformer_ref     VARCHAR(200) NOT NULL,
    inputs              JSONB        NOT NULL,
    output_dtype        VARCHAR(40),
    symbols             TEXT[],
    intervals           TEXT[],
    code_commit         VARCHAR(64),
    pit_safe            BOOLEAN      NOT NULL,
    publish_schedule    VARCHAR(80),
    ffill_policy        VARCHAR(60),
    max_ffill_age_hours INT,
    backfill_strategy   VARCHAR(60),
    label_for_model     VARCHAR(120),
    label_direction     VARCHAR(20),
    status              VARCHAR(20)  NOT NULL DEFAULT 'registered',
    created_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(150),
    updated_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(150),
    PRIMARY KEY (feature_name, version),
    CONSTRAINT chk_feature_registry_label_direction
        CHECK (label_direction IS NULL OR label_direction IN ('forward', 'backward')),
    CONSTRAINT chk_feature_registry_family
        CHECK (family IN ('macro', 'positioning', 'flow', 'market_structure', 'event', 'label', 'technical', 'onchain', 'sentiment'))
);

CREATE INDEX IF NOT EXISTS idx_feature_registry_family
    ON feature_registry (family, status);
CREATE INDEX IF NOT EXISTS idx_feature_registry_status
    ON feature_registry (status);

CREATE TABLE IF NOT EXISTS feature_compute_run (
    run_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_name    VARCHAR(120) NOT NULL,
    version         INT          NOT NULL,
    symbol          VARCHAR(20),
    interval        VARCHAR(10),
    range_start     TIMESTAMPTZ,
    range_end       TIMESTAMPTZ,
    rows_written    BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error_message   TEXT,
    created_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(150),
    updated_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(150),
    CONSTRAINT chk_feature_compute_run_status
        CHECK (status IN ('pending', 'running', 'done', 'failed', 'cancelled')),
    CONSTRAINT fk_feature_compute_run_feature
        FOREIGN KEY (feature_name, version)
        REFERENCES feature_registry (feature_name, version)
);

CREATE INDEX IF NOT EXISTS idx_feature_compute_run_feature
    ON feature_compute_run (feature_name, version, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_feature_compute_run_status
    ON feature_compute_run (status, started_at DESC)
    WHERE status IN ('pending', 'running');

COMMENT ON TABLE feature_registry IS
    'V66 — Registry of features + labels. Every transformer registered here goes through a PIT static check at /features/register. label_direction=''forward'' for labels (read future), ''backward'' for inputs (read past only).';
COMMENT ON COLUMN feature_registry.label_for_model IS
    'V66 — NULL for input features. For labels: name of the consuming model (e.g. ''regime_btc_v1''). Locked sub-model labels per blueprint section 5.6.';

-- ────────────────────────────────────────────────────────────────────────
-- GROUP C — Model layer
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS model_registry (
    id                                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    family                              VARCHAR(40)  NOT NULL,
    purpose                             VARCHAR(40)  NOT NULL,
    symbol                              VARCHAR(20)  NOT NULL,
    interval                            VARCHAR(10),
    horizon_bars                        INT,
    training_intervals                  TEXT[],
    serving_interval                    VARCHAR(10),
    parent_model_id                     UUID,
    feature_set                         JSONB        NOT NULL,
    training_window                     TSTZRANGE,
    validation_window                   TSTZRANGE,
    holdout_window                      TSTZRANGE,
    hyperparams                         JSONB,
    metrics                             JSONB,
    bootstrap_metrics                   JSONB,
    random_seed                         INT          NOT NULL,
    code_commit                         VARCHAR(64),
    artifact_uri                        TEXT,
    artifact_sha256                     VARCHAR(64),
    artifact_size_bytes                 BIGINT,
    artifact_synced_to_vps              BOOLEAN      NOT NULL DEFAULT FALSE,
    artifact_synced_at                  TIMESTAMPTZ,
    strategy_definition_id_at_train     INT,
    strategy_params_hash_at_train       VARCHAR(64),
    status                              VARCHAR(20)  NOT NULL DEFAULT 'training',
    promoted_by                         VARCHAR(150),
    promoted_at                         TIMESTAMPTZ,
    reviewer_verdict                    VARCHAR(30),
    reviewer_run_id                     UUID,
    version                             INT          NOT NULL DEFAULT 1,
    created_time                        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by                          VARCHAR(150),
    updated_time                        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by                          VARCHAR(150),
    CONSTRAINT chk_model_registry_purpose
        CHECK (purpose IN ('regime', 'positioning', 'flow', 'directional', 'meta_label', 'stacker')),
    CONSTRAINT chk_model_registry_status
        CHECK (status IN ('training', 'trained', 'staged', 'shadow', 'cooling_down', 'live', 'retired', 'awaiting_operator_review', 'rejected_by_operator')),
    CONSTRAINT fk_model_registry_parent
        FOREIGN KEY (parent_model_id) REFERENCES model_registry (id),
    CONSTRAINT uq_model_registry_directional
        UNIQUE (purpose, symbol, interval, horizon_bars, version)
);

CREATE INDEX IF NOT EXISTS idx_model_registry_status_purpose
    ON model_registry (status, purpose, symbol);
CREATE INDEX IF NOT EXISTS idx_model_registry_live
    ON model_registry (purpose, symbol, interval, horizon_bars)
    WHERE status = 'live';

CREATE TABLE IF NOT EXISTS training_run (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id                    UUID         NOT NULL REFERENCES model_registry (id),
    fold_n                      INT,
    features_offered            TEXT[],
    features_selected           TEXT[],
    feature_selection_method    VARCHAR(60),
    capacity_estimate_usd       NUMERIC(20, 2),
    capacity_method             VARCHAR(60),
    data_snapshot_query         TEXT,
    random_seed                 INT          NOT NULL,
    duration_seconds            INT,
    status                      VARCHAR(20)  NOT NULL DEFAULT 'pending',
    metrics                     JSONB,
    bootstrap_metrics           JSONB,
    fold_specific               JSONB,
    error_message               TEXT,
    started_at                  TIMESTAMPTZ,
    finished_at                 TIMESTAMPTZ,
    created_time                TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by                  VARCHAR(150),
    updated_time                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by                  VARCHAR(150),
    CONSTRAINT chk_training_run_status
        CHECK (status IN ('pending', 'running', 'done', 'failed', 'cancelled'))
);

CREATE INDEX IF NOT EXISTS idx_training_run_model
    ON training_run (model_id, fold_n);
CREATE INDEX IF NOT EXISTS idx_training_run_status
    ON training_run (status, started_at DESC)
    WHERE status IN ('pending', 'running');

COMMENT ON TABLE model_registry IS
    'V66 — Registry of trained models. Content-addressable via artifact_sha256. Inference worker verifies sha on every load — refuses corrupt or unsynced artifacts. UNIQUE constraint prevents duplicate (purpose, symbol, interval, horizon_bars, version) tuples.';
COMMENT ON COLUMN model_registry.parent_model_id IS
    'V66 — For meta_label models: points to the primary model whose predictions feed this stacker. NULL for primary models.';
COMMENT ON COLUMN model_registry.strategy_definition_id_at_train IS
    'V66 — For meta-label models trained against a specific strategy''s trade outcomes: snapshot of which strategy_definition.id was used. If strategy params change, model is flagged for retraining.';
COMMENT ON COLUMN model_registry.bootstrap_metrics IS
    'V66 — Per-fold bootstrap CIs (1000 resamples). Gauntlet gate 3 uses CI-lower-5% per fold, not point estimates.';

-- ────────────────────────────────────────────────────────────────────────
-- GROUP D — Signal layer
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS signal_definition (
    signal_id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(120) NOT NULL UNIQUE,
    model_id        UUID         NOT NULL REFERENCES model_registry (id),
    horizon         INTERVAL,
    value_range     JSONB,
    description     TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(150),
    updated_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(150),
    CONSTRAINT chk_signal_definition_status
        CHECK (status IN ('active', 'shadow', 'retired'))
);

CREATE TABLE IF NOT EXISTS signal_history (
    signal_id       UUID             NOT NULL REFERENCES signal_definition (signal_id),
    symbol          VARCHAR(20)      NOT NULL,
    ts              TIMESTAMPTZ      NOT NULL,
    value           DOUBLE PRECISION NOT NULL,
    confidence      DOUBLE PRECISION,
    produced_at     TIMESTAMPTZ      NOT NULL,
    source          VARCHAR(20)      NOT NULL,
    meta            JSONB,
    created_time    TIMESTAMP        NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(150),
    updated_time    TIMESTAMP        NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(150),
    PRIMARY KEY (signal_id, symbol, ts),
    CONSTRAINT chk_signal_history_source
        CHECK (source IN ('stream', 'catchup_scan', 'historical_replay'))
) PARTITION BY RANGE (ts);

CREATE INDEX IF NOT EXISTS idx_signal_history_symbol_ts
    ON signal_history (symbol, ts DESC);
CREATE INDEX IF NOT EXISTS idx_signal_history_produced_at
    ON signal_history (produced_at DESC);

DO $$
DECLARE
    d DATE := DATE '2024-12-01';
    end_d DATE := DATE '2028-01-01';
    next_d DATE;
    part_name TEXT;
BEGIN
    WHILE d < end_d LOOP
        next_d := d + INTERVAL '1 month';
        part_name := 'signal_history_' || TO_CHAR(d, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF signal_history '
            'FOR VALUES FROM (%L) TO (%L);',
            part_name, d, next_d
        );
        d := next_d;
    END LOOP;
END $$;

-- NOTE: column is `health_window`, NOT `window` — `window` is a PostgreSQL
-- reserved keyword (window functions per SQL:2003) and trips parser before
-- it even tries to treat it as an identifier. The entity field name on the
-- Java side mirrors this (`MlSourceHealth.healthWindow`).
CREATE TABLE IF NOT EXISTS signal_health (
    signal_id           UUID             NOT NULL REFERENCES signal_definition (signal_id),
    health_window       VARCHAR(20)      NOT NULL,
    trades_count        INT,
    ic                  DOUBLE PRECISION,
    ir                  DOUBLE PRECISION,
    hit_rate            DOUBLE PRECISION,
    realized_sharpe     DOUBLE PRECISION,
    decay_halflife      INTERVAL,
    capacity_usd        DOUBLE PRECISION,
    coverage_pct        DOUBLE PRECISION,
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    created_time        TIMESTAMP        NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(150),
    updated_time        TIMESTAMP        NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(150),
    PRIMARY KEY (signal_id, health_window),
    CONSTRAINT chk_signal_health_window
        CHECK (health_window IN ('7d', '30d', '90d', 'lifetime'))
);

COMMENT ON TABLE signal_history IS
    'V66 — Per-bar signal value, audit-grade. Partitioned monthly on ts. Live trading reads Redis hot key for sub-ms latency; this table is the durable record for backtest/research. source=''historical_replay'' for M5c backfill rows.';
COMMENT ON COLUMN signal_history.source IS
    'V66 — How the row was produced: ''stream'' (Redis bar_closed consumer), ''catchup_scan'' (startup backfill of recent missed bars), ''historical_replay'' (M5c full historical replay over feature_store).';

-- ────────────────────────────────────────────────────────────────────────
-- GROUP E — Research workflow (new — these tables don't exist yet)
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS research_runs (
    id                              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    hypothesis                      TEXT         NOT NULL,
    branch                          VARCHAR(20)  NOT NULL,
    target_artifact                 VARCHAR(200),
    sweep_spec                      JSONB,
    feature_set                     JSONB,
    horizon                         VARCHAR(40),
    unconventional_methodology      BOOLEAN      NOT NULL DEFAULT FALSE,
    override_rationale              TEXT,
    status                          VARCHAR(30)  NOT NULL DEFAULT 'pre_registered',
    verdict                         VARCHAR(30),
    verdict_details                 JSONB,
    submitted_by                    VARCHAR(150),
    submitted_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at                    TIMESTAMPTZ,
    created_time                    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by                      VARCHAR(150),
    updated_time                    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by                      VARCHAR(150),
    CONSTRAINT chk_research_runs_branch
        CHECK (branch IN ('ALGO', 'DL', 'HYBRID', 'DL_STANDALONE')),
    CONSTRAINT chk_research_runs_status
        CHECK (status IN ('pre_registered', 'reviewing', 'approved', 'running', 'complete', 'rejected', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_research_runs_status_branch
    ON research_runs (status, branch, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_research_runs_branch_verdict
    ON research_runs (branch, verdict, completed_at DESC)
    WHERE verdict IS NOT NULL;

CREATE TABLE IF NOT EXISTS reviews (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    research_run_id     UUID         REFERENCES research_runs (id),
    model_id            UUID         REFERENCES model_registry (id),
    reviewer            VARCHAR(120) NOT NULL,
    verdict             VARCHAR(30)  NOT NULL,
    rationale           TEXT,
    checklist           JSONB,
    submitted_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(150),
    updated_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(150),
    CONSTRAINT chk_reviews_verdict
        CHECK (verdict IN ('APPROVED', 'CONDITIONAL_APPROVAL', 'REJECTED')),
    CONSTRAINT chk_reviews_target
        CHECK (research_run_id IS NOT NULL OR model_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_reviews_research_run
    ON reviews (research_run_id, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_reviews_model
    ON reviews (model_id, submitted_at DESC);

COMMENT ON TABLE research_runs IS
    'V66 — Pre-registered research hypotheses. Agent posts here BEFORE any sweep/training begins. Reviewer agent audits and posts to reviews table. Branch determines strictness of reviewer checklist: ALGO=simplified, DL/HYBRID/DL_STANDALONE=full nested-CV.';
COMMENT ON TABLE reviews IS
    'V66 — Reviewer agent verdicts. checklist JSONB carries structured pass/fail per gate (see project_ml_blueprint.md section 11.1). Reviewer cannot self-approve; orchestrator''s /promotion endpoint enforces.';

-- ────────────────────────────────────────────────────────────────────────
-- GROUP F — Promotion & audit
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS model_promotion_gauntlet (
    model_id                                UUID         PRIMARY KEY REFERENCES model_registry (id),
    iteration_n                             INT          NOT NULL DEFAULT 1,
    parent_gauntlet_id                      UUID         REFERENCES model_promotion_gauntlet (model_id),

    gate_1_labels_pit_safe                  BOOLEAN,
    gate_2_ensemble_sane                    BOOLEAN,
    gate_3_walk_forward_all_folds_pass      BOOLEAN,
    gate_4_adversarial_audit_pass           BOOLEAN,
    gate_5_cost_stress_pass                 BOOLEAN,
    gate_6_regime_subcuts_pass              BOOLEAN,
    gate_7_capacity_sufficient              BOOLEAN,
    gate_8_reviewer_approved                BOOLEAN,
    gate_9_stat_rigor_pass                  BOOLEAN,
    gate_10_walk_forward_robust             BOOLEAN,
    gate_11_shadow_validated                BOOLEAN,
    gate_12_operator_approved               BOOLEAN,
    gate_13_retraining_stability            BOOLEAN,

    prediction_correlation_to_previous      NUMERIC(6, 4),
    previous_model_id                       UUID         REFERENCES model_registry (id),

    current_gate                            INT          NOT NULL DEFAULT 0,
    failed_at_gate                          INT,
    failure_reason                          TEXT,

    approved_at                             TIMESTAMPTZ,
    cooldown_until                          TIMESTAMPTZ,
    promoted_at                             TIMESTAMPTZ,
    completed_at                            TIMESTAMPTZ,

    created_time                            TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by                              VARCHAR(150),
    updated_time                            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by                              VARCHAR(150),

    CONSTRAINT chk_gauntlet_cooldown_respected
        CHECK (promoted_at IS NULL OR cooldown_until IS NULL OR promoted_at >= cooldown_until),
    CONSTRAINT chk_gauntlet_current_gate_range
        CHECK (current_gate BETWEEN 0 AND 13)
);

CREATE INDEX IF NOT EXISTS idx_gauntlet_active
    ON model_promotion_gauntlet (current_gate, model_id)
    WHERE completed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_gauntlet_cooldown
    ON model_promotion_gauntlet (cooldown_until)
    WHERE cooldown_until IS NOT NULL AND promoted_at IS NULL;

CREATE TABLE IF NOT EXISTS order_audit (
    order_id                BIGINT       NOT NULL,
    decided_at              TIMESTAMPTZ  NOT NULL,
    strategy_id             INT          NOT NULL,
    strategy_signal_type    VARCHAR(40),
    strategy_size_raw       NUMERIC(28, 10),
    ml_signal_ids           JSONB,
    ml_signal_values        JSONB,
    ml_signal_freshness_ms  JSONB,
    feature_store_bar_ts    TIMESTAMPTZ,
    feature_set_hash        VARCHAR(64),
    guard_chain             JSONB,
    sizing_chain            JSONB,
    final_size              NUMERIC(28, 10),
    exit_type               VARCHAR(20),
    ml_global_enabled       BOOLEAN,
    ml_strategy_enabled     BOOLEAN,
    created_time            TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(150),
    updated_time            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by              VARCHAR(150),
    PRIMARY KEY (order_id, decided_at),
    CONSTRAINT chk_order_audit_exit_type
        CHECK (exit_type IS NULL OR exit_type IN ('tp_hit', 'sl_hit', 'horizon_end', 'manual', 'liquidation'))
) PARTITION BY RANGE (decided_at);

CREATE INDEX IF NOT EXISTS idx_order_audit_strategy_decided
    ON order_audit (strategy_id, decided_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_audit_decided_at
    ON order_audit (decided_at DESC);

DO $$
DECLARE
    d DATE := DATE '2026-05-01';
    end_d DATE := DATE '2028-01-01';
    next_d DATE;
    part_name TEXT;
BEGIN
    WHILE d < end_d LOOP
        next_d := d + INTERVAL '1 month';
        part_name := 'order_audit_' || TO_CHAR(d, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF order_audit '
            'FOR VALUES FROM (%L) TO (%L);',
            part_name, d, next_d
        );
        d := next_d;
    END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS ml_kill_switch_audit (
    id              BIGSERIAL    PRIMARY KEY,
    ts              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor           VARCHAR(120) NOT NULL,
    scope           VARCHAR(80)  NOT NULL,
    enabled         BOOLEAN      NOT NULL,
    reason          TEXT,
    created_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(150),
    updated_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(150)
);

CREATE INDEX IF NOT EXISTS idx_ml_kill_switch_audit_ts
    ON ml_kill_switch_audit (ts DESC);
CREATE INDEX IF NOT EXISTS idx_ml_kill_switch_audit_scope
    ON ml_kill_switch_audit (scope, ts DESC);

CREATE TABLE IF NOT EXISTS reviewer_audit (
    audit_id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    audited_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    auditor         VARCHAR(120) NOT NULL,
    review_id       UUID         NOT NULL REFERENCES reviews (id),
    audit_verdict   VARCHAR(30)  NOT NULL,
    notes           TEXT,
    created_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(150),
    updated_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(150),
    CONSTRAINT chk_reviewer_audit_verdict
        CHECK (audit_verdict IN ('CONFIRMED', 'OVERRIDDEN', 'CONCERN_RAISED'))
);

CREATE INDEX IF NOT EXISTS idx_reviewer_audit_review
    ON reviewer_audit (review_id);
CREATE INDEX IF NOT EXISTS idx_reviewer_audit_audited_at
    ON reviewer_audit (audited_at DESC);

CREATE TABLE IF NOT EXISTS researcher_override_budget (
    quarter         VARCHAR(10)  PRIMARY KEY,
    overrides_used  INT          NOT NULL DEFAULT 0,
    overrides_max   INT          NOT NULL DEFAULT 3,
    created_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(150),
    updated_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(150),
    CONSTRAINT chk_override_budget_used_nonneg
        CHECK (overrides_used >= 0),
    CONSTRAINT chk_override_budget_max_pos
        CHECK (overrides_max > 0)
);

INSERT INTO researcher_override_budget (quarter, overrides_used, overrides_max, created_by)
VALUES ('2026-Q2', 0, 3, 'V66 migration')
ON CONFLICT (quarter) DO NOTHING;

COMMENT ON TABLE model_promotion_gauntlet IS
    'V66 — 13-gate promotion gauntlet per model. cooldown_until = approved_at + 7 days. promoted_at cannot precede cooldown_until (CHECK enforced). iteration_n + parent_gauntlet_id link successive attempts for the same hypothesis — reviewer''s DSR threshold scales with cumulative iterations.';
COMMENT ON TABLE order_audit IS
    'V66 — Per-order ML decision snapshot, partitioned monthly on decided_at, retained forever. Written inside the same transaction as the order. Reconstructs full decision context months later for audit. guard_chain JSONB has ordered list of {guard, verdict, reason} entries.';
COMMENT ON TABLE ml_kill_switch_audit IS
    'V66 — Log of every ml:enabled / ml:enabled:{strategy} flip. actor (e.g. ''operator:rifki'', ''agent:reviewer''), scope (''global'' or ''strategy:LSR''), reason.';

-- ────────────────────────────────────────────────────────────────────────
-- GROUP G — ALTERs to existing tables
-- ────────────────────────────────────────────────────────────────────────

-- strategy_definition: per-strategy ML mode toggle
ALTER TABLE strategy_definition
    ADD COLUMN IF NOT EXISTS ml_mode VARCHAR(20) NOT NULL DEFAULT 'OFF',
    ADD COLUMN IF NOT EXISTS ml_mode_shadow BOOLEAN NOT NULL DEFAULT TRUE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'strategy_definition'
          AND constraint_name = 'chk_strategy_definition_ml_mode'
    ) THEN
        ALTER TABLE strategy_definition
            ADD CONSTRAINT chk_strategy_definition_ml_mode
            CHECK (ml_mode IN ('OFF', 'HYBRID'));
    END IF;
END $$;

COMMENT ON COLUMN strategy_definition.ml_mode IS
    'V66 — Per-strategy ML mode. OFF (default) = pure algo, no ML influence. HYBRID = ML guards/sizing modifiers active in the risk chain. Backfilled OFF for all existing rows so live behaviour is unchanged until operator explicitly opts in.';
COMMENT ON COLUMN strategy_definition.ml_mode_shadow IS
    'V66 — Shadow flag. When TRUE (default), HYBRID decisions are logged to order_audit but NOT enforced. Flip to FALSE only after shadow validation evidence is reviewed (Phase 4 → 5 gate).';

-- account_strategy: per-symbol exposure cap across all active strategies
ALTER TABLE account_strategy
    ADD COLUMN IF NOT EXISTS per_symbol_exposure_cap_pct NUMERIC(6, 4) DEFAULT 1.5000;

COMMENT ON COLUMN account_strategy.per_symbol_exposure_cap_pct IS
    'V66 — Cap on total notional exposure per (account, symbol) across all active strategies. Used by MultiStrategyExposureGuard (new in Phase 4 / M7). Default 1.5 = 150% of account equity. Prevents HYBRID-modulated LSR + ML_DIRECTIONAL stacking same-symbol longs beyond intended risk.';

-- ────────────────────────────────────────────────────────────────────────
-- GROUP H — Seed ML_DIRECTIONAL strategy_definition row
-- ────────────────────────────────────────────────────────────────────────
--
-- ML_DIRECTIONAL is a new strategy archetype. Spec-driven (archetype != 'LEGACY_JAVA');
-- spec_jsonb defines its params. Default disabled + simulated so it doesn't
-- accidentally fire until M7 promotes a real model and operator opts in.
--
-- Required baseline columns (V1__baseline.sql:115-136 — all NOT NULL,
-- no defaults for these four):
--   strategy_definition_id  — UUID PK, must be supplied
--   strategy_name           — user-visible label
--   strategy_type           — coarse taxonomy (one of LEGACY_JAVA/SPEC/ML)
--   status                  — promotion state (RESEARCH per V15/V40 pipeline
--                              — new strategies start there and walk forward
--                              via /api/v1/strategy-promotion/definition/...)

INSERT INTO strategy_definition (
    strategy_definition_id,
    strategy_code,
    strategy_name,
    strategy_type,
    description,
    status,
    archetype,
    archetype_version,
    spec_jsonb,
    enabled,
    simulated,
    ml_mode,
    ml_mode_shadow,
    created_by
)
VALUES (
    gen_random_uuid(),
    'ML_DIRECTIONAL',
    'ML Directional (standalone)',
    'ML',
    'Standalone ML-driven directional strategy. Triple-barrier + meta-labeling ensemble. See project_ml_blueprint.md.',
    'RESEARCH',
    'ML_DIRECTIONAL',
    1,
    '{
      "description": "Standalone ML-driven directional strategy. Triple-barrier + meta-labeling ensemble. See project_ml_blueprint.md.",
      "params": {
        "interval":              {"type": "enum",  "values": ["5m","15m","1h","4h"], "default": "1h"},
        "horizon_class":         {"type": "enum",  "values": ["short","medium","long"], "default": "medium"},
        "confidence_threshold":  {"type": "float", "min": 0.50, "max": 0.80, "default": 0.55},
        "tp_atr_mult":           {"type": "float", "min": 0.50, "max": 3.00, "default": 1.50},
        "sl_atr_mult":           {"type": "float", "min": 0.30, "max": 2.00, "default": 1.00},
        "half_kelly_factor":     {"type": "float", "min": 0.25, "max": 1.00, "default": 0.50},
        "time_exit_bars":        {"type": "int",   "default": "computed_from_horizon_class"}
      }
    }'::jsonb,
    FALSE,   -- enabled=false: refuse to fire until operator opts in via promotion
    TRUE,    -- simulated=true: paper-trade only on first opt-in
    'OFF',
    TRUE,
    'V66 migration'
)
ON CONFLICT (strategy_code) DO NOTHING;

-- ────────────────────────────────────────────────────────────────────────
-- GROUP I — Role grants / revokes
-- ────────────────────────────────────────────────────────────────────────
--
-- blackheart_research (research-orchestrator + training workers):
--   READ everything operational; WRITE research-side tables ONLY.
--   No DELETE on registries (prevents agent accidentally wiping artifacts).
--   No writes on live trading tables.
--
-- blackheart_trading (trading JVM):
--   READ + WRITE live operational tables, READ research tables.
--   No writes on registries / model artifacts (those come from research side).
--
-- Roles are created in V14 — we only grant/revoke here; they already exist
-- with NOLOGIN locally (developers connect as the superuser).

DO $$
BEGIN
    -- blackheart_research: SELECT on read-side tables
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_research') THEN
        EXECUTE 'GRANT SELECT ON macro_raw, onchain_raw, news_raw, social_raw TO blackheart_research';
        EXECUTE 'GRANT SELECT ON feature_registry, feature_compute_run TO blackheart_research';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON feature_registry TO blackheart_research';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON feature_compute_run TO blackheart_research';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON model_registry, training_run TO blackheart_research';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON signal_definition, signal_history, signal_health TO blackheart_research';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON model_promotion_gauntlet TO blackheart_research';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON research_runs, reviews, reviewer_audit, researcher_override_budget TO blackheart_research';

        -- No DELETE on registries (agent must not wipe artifacts)
        EXECUTE 'REVOKE DELETE ON model_registry, feature_registry, signal_definition, training_run FROM blackheart_research';
        -- No writes on live trading tables — research is read-only there
        EXECUTE 'REVOKE INSERT, UPDATE, DELETE ON order_audit, ml_kill_switch_audit FROM blackheart_research';
        EXECUTE 'GRANT SELECT ON order_audit, ml_kill_switch_audit TO blackheart_research';
    END IF;

    -- blackheart_trading: write live, read research
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'blackheart_trading') THEN
        -- Live ingestion + inference worker write paths
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON macro_raw, onchain_raw, news_raw, social_raw TO blackheart_trading';
        -- Inference worker writes signal_history
        EXECUTE 'GRANT SELECT, INSERT ON signal_history TO blackheart_trading';
        EXECUTE 'GRANT SELECT ON signal_definition, signal_health TO blackheart_trading';
        -- Trading JVM writes order_audit + kill switch audit
        EXECUTE 'GRANT SELECT, INSERT ON order_audit TO blackheart_trading';
        EXECUTE 'GRANT SELECT, INSERT ON ml_kill_switch_audit TO blackheart_trading';
        -- Trading JVM reads model_registry + feature_registry to know what's live
        EXECUTE 'GRANT SELECT ON model_registry, feature_registry, feature_compute_run TO blackheart_trading';
        EXECUTE 'GRANT SELECT ON model_promotion_gauntlet TO blackheart_trading';
        -- Trading doesn''t touch research tables
        EXECUTE 'REVOKE ALL ON research_runs, reviews, reviewer_audit, researcher_override_budget FROM blackheart_trading';
        EXECUTE 'GRANT SELECT ON research_runs, reviews TO blackheart_trading';
    END IF;
END $$;
