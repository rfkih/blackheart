# Key tables — schema reference

> One-liner per important table so agents don't open `V1__baseline.sql`
> (1700+ lines) just to remember a column name. For the full DDL, the
> path + line number lets you `Read` directly. For the migration history
> (V14–V40), see `MIGRATIONS.md`.

## Conventions

- **Source of truth:** `src/main/resources/db/flyway/V1__baseline.sql` for the baseline state, `V<N>__*.sql` for incremental changes.
- **Persistence rule:** `@JdbcTypeCode(SqlTypes.JSON)` for JSONB (NOT `AttributeConverter`). See WORKING_RULES.md.
- **Cache eviction:** Redis cache evict via `afterCommit()` (TransactionSynchronizationManager). Never inside `@Transactional`.

## Market & feature plane

| Table | Path | Purpose / key columns |
|---|---|---|
| `market_data` | `V1__baseline.sql:11` | OHLCV + Binance metadata (`trade_count`, `quote_asset_volume`, `taker_buy_*`). Unique on `(symbol, interval, start_time)`. Parent for `feature_store`. |
| `feature_store` | `V1__baseline.sql:32` | All technical indicators per bar (EMA, ADX, RSI, Donchian, Bollinger, ATR, MACD, slope_200, funding cols). Unique on `(symbol, interval, start_time)`. Funding columns added V35. |
| `funding_rate_history` | `V1__baseline.sql:1343` | Binance perpetual funding events. PK `(symbol, funding_time)`. Source for `feature_store` funding columns. Backfilled by `BACKFILL_FUNDING_HISTORY`, kept fresh by `FUNDING_INGEST` scheduler (V37, every 8h UTC). |

## Strategy / promotion plane

| Table | Path | Purpose / key columns |
|---|---|---|
| `strategy_definition` | `V1__baseline.sql:115` | Strategy archetype registry (`archetype`, `archetype_version`, `spec_jsonb`). `enabled` + `simulated` (V40 definition-scope) gate live execution. |
| `strategy_param` | redesigned in V29 | 1:N saved presets per `account_strategy`. `is_active` flags the live preset; soft-deleted presets remain resolvable for backtest reproducibility. PK `param_id`. |
| `account_strategy` | search the file | Per-account strategy enablement; `simulated` flag (V15) diverts OPEN_LONG/OPEN_SHORT only — see WORKING_RULES.md. `visibility` (V54) is `PRIVATE` (default) or `PUBLIC`; PUBLIC rows are listed to every user for browse-and-clone (research-agent's catalogue is PUBLIC). Backtest/edit/enable still gated on ownership regardless of visibility. **V62**: gate toggles `kill_switch_gate_enabled` / `regime_gate_enabled` (V43) / `correlation_gate_enabled` / `concurrent_cap_gate_enabled` — all default FALSE; `RiskGuardService.evaluate` skips each gate whose toggle is off. |

## Research plane

| Table | Path | Purpose / key columns |
|---|---|---|
| `backtest_run` | `V1__baseline.sql:424` | One backtest execution. Config snapshot, metrics (return, Sharpe, Sortino, max drawdown, profit factor), `triggered_by` (USER/RESEARCHER), `strategy_param_ids` JSONB pinning preset rows. V60 adds `avg_trade_return_pct` + `geometric_return_pct_at_alloc_90` — sizing-independent companions to `return_pct`. **V62**: four nullable JSONB override columns (`strategy_kill_switch_overrides`, `strategy_regime_overrides`, `strategy_correlation_overrides`, `strategy_concurrent_cap_overrides`) — per-run per-strategy gate toggle overrides; null/missing key falls back to `account_strategy.<gate>_gate_enabled`. |
| `research_iteration_log` | `V1__baseline.sql:899` | Pre-registered research hypothesis + verdict (`PASS`/`ITERATE`/`DISCARD`/`FAILED`) and statistical verdict (`SIGNIFICANT_EDGE`/`NO_EDGE`/...). Stat-rigor gate from V11 enforced (n≥100, PF 95% CI lower>1.0). |
| `walk_forward_run` | `V1__baseline.sql:992` | Out-of-sample stability verdict (`ROBUST`/`OVERFIT`/`NO_EDGE`) across n-fold train/test windows. Gates promotion from `SIGNIFICANT_EDGE` → production-eligible. |
| `cross_window_run` | added V38 | Regime-labeled epoch results. `ROBUST_CROSS_WINDOW` = ≥80% windows net-positive after +20bps slippage. Window defs in `research-orchestrator/config/regime_windows.yml`. |
| `spec_trace` | added V19 | Per-bar spec evaluation trace (backtest dense, live 1% sample). For debugging "why did the strategy decide X on this bar?". |

## Operational plane

| Table | Path | Purpose / key columns |
|---|---|---|
| `error_log` | `V1__baseline.sql:791` | Centralized exception fingerprinting. Unique fingerprint when `status IN (NEW, INVESTIGATING)` — dedupes recurring errors. Severity, occurrence count, optional `code_review_finding_id` link. |
| `historical_backfill_job` | search | Job rows persisted by `HistoricalBackfillJobAsyncRunner`. State: `PENDING` → `RUNNING` → `SUCCESS` / `FAILED` / `CANCELLED`. Carries `job_type`, `params` JSONB, `result` JSONB, `cancel_requested`. |
| `scheduler_job_last_run` | added V27 | One row per scheduled job (`FUNDING_INGEST`, etc.) — last successful tick, used by liveness watchdog. |
| `idempotency_record` | added V28 | TTL ~24h table backing the FastAPI orchestrator's idempotency keys. |
| `research_control` | added V23 | Kill-switch + global research flags (e.g. `live_research_enabled`). |

## ML / sentiment plane (V66, Phase 1 of project_ml_blueprint.md)

> Symbol-keyed throughout; BTC-first, ETH/others add via config not migration. All tables follow `BaseEntity` (4 audit cols: `created_time`, `created_by`, `updated_time`, `updated_by`).

### Raw data (4 tables, monthly-partitioned by `event_time`)

| Table | Path | Purpose / key columns |
|---|---|---|
| `macro_raw` | added V66 | Macro feeds: FRED/ALFRED (DXY, real yields, VIX, M2), CoinGecko (dominance), Binance funding/OI/L/S, alternative.me F&G. Dual timestamps (`event_time` publisher, `ingestion_time` ours). `series_id` joins to `feature_registry.inputs`. UNIQUE on `(source, source_uri, event_time)`. |
| `onchain_raw` | added V66 | DefiLlama stablecoin supply, CoinMetrics community netflow + active-addresses. Same shape as `macro_raw`. |
| `news_raw` | added V66 | Empty in v1 — populated in V2 / Phase 7a. `body_uri` points to blob storage; PG holds metadata + `sentiment_score`. |
| `social_raw` | added V66 | Empty in v1 — V2 deferred. Reddit/etc. |

### Registries

| Table | Path | Purpose / key columns |
|---|---|---|
| `feature_registry` | added V66 | Catalog of features + labels. PK `(feature_name, version)`. `pit_safe`, `publish_schedule`, `ffill_policy`, `max_ffill_age_hours`, `backfill_strategy`, `label_for_model`, `label_direction` (`forward`/`backward`). Static check enforces no PIT violations at `/features/register`. |
| `feature_compute_run` | added V66 | Audit log of feature backfill/compute ops. Status `pending`→`running`→`done`/`failed`/`cancelled`. FK to `feature_registry`. |

### Model layer

| Table | Path | Purpose / key columns |
|---|---|---|
| `model_registry` | added V66 | First-class artifact registry. `artifact_sha256` enforced verification on every inference load. `purpose IN (regime, positioning, flow, directional, meta_label, stacker)`. `parent_model_id` links meta-labels to their primary. `bootstrap_metrics` JSONB carries per-fold CIs. UNIQUE on `(purpose, symbol, interval, horizon_bars, version)`. `strategy_definition_id_at_train` binds meta-labels to a specific strategy state. |
| `training_run` | added V66 | Per-fold training audit. `features_offered`/`features_selected` track Fix 5 feature selection. `capacity_estimate_usd` + `capacity_method`. `random_seed` mandatory for reproducibility. |

### Signal layer

| Table | Path | Purpose / key columns |
|---|---|---|
| `signal_definition` | added V66 | Catalog of signal streams (e.g. `regime_btc_1h_v1`). One row per producing model. `value_range` JSONB. |
| `signal_history` | added V66 | Per-bar signal values, partitioned monthly on `ts`. PK `(signal_id, symbol, ts)`. `source IN (stream, catchup_scan, historical_replay)` — distinguishes how the row was produced. Live trading reads Redis hot key; this table is durable audit + research input. |
| `signal_health` | added V66 | Rolling metrics per signal: IC, IR, hit rate, decay halflife, capacity, coverage. `health_window` column (NOT `window` — keyword conflict): `7d`/`30d`/`90d`/`lifetime`. Recomputed quarterly (Phase 6). |

### Research workflow

| Table | Path | Purpose / key columns |
|---|---|---|
| `research_runs` | added V66 | Pre-registered hypotheses. Agent posts BEFORE any sweep/training. `branch IN (ALGO, DL, HYBRID, DL_STANDALONE)` selects reviewer checklist strictness. `unconventional_methodology` + `override_rationale` (Fix 15 — researcher override budget). |
| `reviews` | added V66 | Reviewer agent verdicts (`APPROVED`/`CONDITIONAL_APPROVAL`/`REJECTED`). `checklist` JSONB carries structured pass/fail per gate. Either `research_run_id` or `model_id` non-null (CHECK enforced). |

### Promotion & audit

| Table | Path | Purpose / key columns |
|---|---|---|
| `model_promotion_gauntlet` | added V66 | 13-gate gauntlet per model (gate 1: PIT labels … gate 12: operator approval + 7-day cooldown … gate 13: retraining stability ≥ 0.6 corr). `iteration_n` + `parent_gauntlet_id` link successive attempts (DSR threshold scales with cumulative iterations per Fix 8). `cooldown_until` enforced via CHECK constraint. |
| `order_audit` | added V66 | Per-order ML decision snapshot. Partitioned monthly on `decided_at`, retained forever. PK `(order_id, decided_at)`. `guard_chain`/`sizing_chain` JSONB ordered lists. `feature_set_hash` + `feature_store_bar_ts` reconstruct full decision context. `exit_type IN (tp_hit, sl_hit, horizon_end, manual, liquidation)`. |
| `ml_kill_switch_audit` | added V66 | Log of every `ml:enabled` / `ml:enabled:{strategy}` Redis-flag flip. `actor` (operator/agent/auto), `scope` (`global`/`strategy:LSR`/etc.), `reason`. |
| `reviewer_audit` | added V66 | Operator quarterly meta-audits of reviewer verdicts. `audit_verdict IN (CONFIRMED, OVERRIDDEN, CONCERN_RAISED)`. FK to `reviews`. |
| `researcher_override_budget` | added V66 | Per-quarter override counter. PK `quarter` (e.g. `'2026-Q2'`). `overrides_max` default 3. Seeded with 2026-Q2 row by V66. Reviewer rejects when budget exhausted. |

### Extended in V66

| Table | Column | Purpose |
|---|---|---|
| `strategy_definition` | `ml_mode` (OFF\|HYBRID), `ml_mode_shadow` | Per-strategy ML mode + shadow flag. OFF = pure algo; HYBRID = ML guards/modifiers active. Shadow=TRUE logs decisions to `order_audit` without enforcing. New `ML_DIRECTIONAL` strategy archetype seeded (enabled=false, simulated=true). |
| `account_strategy` | `per_symbol_exposure_cap_pct` | Cap on total notional exposure per (account, symbol) across all strategies. Default 1.5 = 150% account equity. Used by `MultiStrategyExposureGuard` (Phase 4 / M7). |

### Ingestion control plane (V67, admin frontend integration)

> Reuses existing `historical_backfill_job` (V47) for manual backfill execution — no schema change for that. Adds two new tables for admin-managed scheduling + per-source health dashboard.

| Table | Path | Purpose / key columns |
|---|---|---|
| `ml_ingest_schedule` | added V67 | Admin-configurable cron schedules per (source, symbol). `cron_expression` (Spring 6-field) editable via Blackridge admin UI. Spring TaskScheduler reads this on 60s refresh tick → registers dynamic CronTriggers. `config` JSONB carries source-specific params (FRED series IDs, Binance feeds, etc.). `enabled` defaults FALSE — operator opts in per source after Python module verified. UNIQUE on `(source, symbol)`. |
| `ml_source_health` | added V67, renamed V68 | Per-source health snapshot (one row per source). Updated by every live-ingest tick. Drives the Blackridge "ML Data Sources" dashboard. `health_status IN (healthy, degraded, failed, disabled, unknown)`. Tracks `consecutive_failures`, `rows_inserted_total`, `errors_total`, `rejected_pit_violations_total` (V68 renamed from `*_24h` — counters are cumulative lifetime, not rolling). Monitor cron alerts (Telegram) on transitions to degraded/failed. |

### Manual backfill workflow (extends V47 historical_backfill_job)

```
Operator clicks "Backfill FRED 17mo" in Blackridge
  → Java MlIngestController inserts historical_backfill_job row
    {job_type='BACKFILL_ML_FRED', params={start, end, series_ids}, status='PENDING'}
  → HistoricalBackfillJobAsyncRunner picks it up
  → BackfillMlFredHandler (new bean) delegates to Python ingest service via HTTP
  → Python heavy_ingest.py runs pull, writes macro_raw rows
  → Handler tracks progress via ctx.setPhase()/setProgress()
  → Status flips PENDING → RUNNING → SUCCESS/FAILED/CANCELLED
  → Frontend polls GET /api/v1/historical/jobs/{id} for live progress
```

New `JobType` enum values to add (Java code, not migration): `BACKFILL_ML_FRED`, `BACKFILL_ML_BINANCE_MACRO`, `BACKFILL_ML_DEFILLAMA`, `BACKFILL_ML_COINMETRICS`, `BACKFILL_ML_COINGECKO`, `BACKFILL_ML_ALTERNATIVE_ME`, `BACKFILL_ML_FOREXFACTORY`.

## Adding a new table

1. **New Flyway migration** — `V<n>__<verb>_<noun>.sql`. Idempotent (`IF NOT EXISTS`). See WORKING_RULES.md "Migrations are immutable once applied".
2. **Entity** — add the JPA `@Entity` class. Use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB cols.
3. **Repository** — extend `JpaRepository<Entity, Id>` with custom queries.
4. **Catalog** — if the table is in the "key 8" (read by multiple services or by the agent often), add a row above. Routine internal tables don't need to be listed here — they belong in `MIGRATIONS.md`'s V<n> entry.

## Maintenance

Updated **in the same PR** as the migration. See WORKING_RULES.md → "Catalog maintenance" for the trigger list. The bar for inclusion: would the next agent waste tokens grepping for this table's columns? If yes, add it.
