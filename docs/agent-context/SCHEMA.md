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
| `account_strategy` | search the file | Per-account strategy enablement; `simulated` flag (V15) diverts OPEN_LONG/OPEN_SHORT only — see WORKING_RULES.md. `visibility` (V54) is `PRIVATE` (default) or `PUBLIC`; PUBLIC rows are listed to every user for browse-and-clone (research-agent's catalogue is PUBLIC). Backtest/edit/enable still gated on ownership regardless of visibility. |

## Research plane

| Table | Path | Purpose / key columns |
|---|---|---|
| `backtest_run` | `V1__baseline.sql:424` | One backtest execution. Config snapshot, metrics (return, Sharpe, Sortino, max drawdown, profit factor), `triggered_by` (USER/RESEARCHER), `strategy_param_ids` JSONB pinning preset rows. V60 adds `avg_trade_return_pct` + `geometric_return_pct_at_alloc_90` — sizing-independent companions to `return_pct`. |
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

## Adding a new table

1. **New Flyway migration** — `V<n>__<verb>_<noun>.sql`. Idempotent (`IF NOT EXISTS`). See WORKING_RULES.md "Migrations are immutable once applied".
2. **Entity** — add the JPA `@Entity` class. Use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB cols.
3. **Repository** — extend `JpaRepository<Entity, Id>` with custom queries.
4. **Catalog** — if the table is in the "key 8" (read by multiple services or by the agent often), add a row above. Routine internal tables don't need to be listed here — they belong in `MIGRATIONS.md`'s V<n> entry.

## Maintenance

Updated **in the same PR** as the migration. See WORKING_RULES.md → "Catalog maintenance" for the trigger list. The bar for inclusion: would the next agent waste tokens grepping for this table's columns? If yes, add it.
