# Flyway migration history

> Detailed change-log of every Flyway migration. Loaded on demand —
> the top-level `CLAUDE.md` only points to "current head" so the hot
> context doesn't carry the full archive.

## Conventions

- **Source of truth:** `src/main/resources/db/flyway/V<N>__name.sql`. Convention `V<n>__<verb>_<noun>.sql`.
- **Idempotency:** every migration uses `IF NOT EXISTS` / `IF EXISTS`. `ddl-auto=validate` stays on as safety net.
- **Baseline:** pre-Flyway state stamped V1 via `spring.flyway.baseline-on-migrate=true`. Legacy `db/migration/` is reference only.
- **Trading JVM owns Flyway.** Research JVM has Flyway disabled.

## Current head: V68

## V14–V68 catalog

- **V14** — DB role separation (`blackheart_trading` full DML; `blackheart_research` SELECT operational + DML backtest/research/promotion; both NOLOGIN; see `research/DB_USER_SEPARATION.md`).
- **V15** — Promotion pipeline: `account_strategy.simulated`, `paper_trade_run`, `strategy_promotion_log` w/ CHECK.
- **V16** — Unified `strategy_param` (1:1, spec-driven only).
- **V17** — `strategy_definition` spec columns.
- **V18** — `strategy_definition_history`.
- **V19** — `spec_trace` (backtest dense, live 1% sample).
- **V20 / V21** — Postgres LISTEN/NOTIFY for spec mutations.
- **V22** — Funding-rate cols on `backtest_run`.
- **V23** — `research_control` (kill-switch + global flags).
- **V24 / V25** — `error_log` + grant to research role.
- **V26** — `code_review_finding`.
- **V27** — `scheduler_job_last_run`.
- **V28** — `idempotency_record` for FastAPI orchestrator (TTL ~24h).
- **V29** — Redesigns `strategy_param` to 1:N saved presets (PK now `param_id`; `is_active` flags the live preset; soft-deleted presets stay resolvable for backtest reproducibility).
- **V30** — Backfills `strategy_param` from legacy `lsr_/vcb_/vbo_strategy_param` + seeds an empty `default` preset for every account_strategy w/o one.
- **V31** — `backtest_run.strategy_param_ids` JSONB pins specific preset rows by `param_id` (incl. soft-deleted) so analysis doesn't drift when active preset changes.
- **V32** — `backtest_run.triggered_by` (`USER`/`RESEARCHER` tag for frontend filtering; not a security control).
- **V33** — `strategy_param.source_backtest_run_id` (preset provenance; ON DELETE SET NULL).
- **V34** — `funding_rate_history` (Phase 4 — per-symbol 8h Binance funding events; PK `(symbol, funding_time)` idempotent).
- **V35** — `feature_store` funding cols (`funding_rate_8h`, `funding_rate_7d_avg`, `funding_rate_z`).
- **V36** — Seeds FCARRY strategy_definition (`LEGACY_JAVA` archetype → `FundingCarryStrategyService`).
- **V37** — Seeds FUNDING_INGEST scheduler (8h cron, symbols from `app.funding.symbols`).
- **V38** — `cross_window_run` (regime-labeled epochs; ROBUST_CROSS_WINDOW = ≥80% windows net-positive after +20bps slippage; window defs in `research-orchestrator/config/regime_windows.yml`).
- **V39** — `alert_event` append-only log for `AlertService.raise()` (Phase 7.1; logged even when dedup suppresses outbound).
- **V40** — Promotes lifecycle from per-account to definition-scope: adds `strategy_definition.enabled`/`.simulated` (CHECK matches account-scope); makes `strategy_promotion_log.account_strategy_id` nullable + adds `.strategy_definition_id` w/ `chk_promotion_log_scope` (exactly one of the two set per row); backfills `strategy_definition` from most-permissive existing `account_strategy` state per `strategy_code`.
- **V54** — Dedicated research-agent user (`research-agent@blackheart.local`, pinned UUID `99999999-…-001`) + account (pinned UUID `99999999-…-002`); adds `account_strategy.visibility` (`PRIVATE`|`PUBLIC`, default `PRIVATE`) + partial index `idx_account_strategy_public`. Copies every `(strategy_code, symbol, interval_name)` referenced by `research_iteration_log ∪ research_queue` onto the agent's account as `visibility=PUBLIC` / `simulated=true` / `enabled=false` clones (admin's originals untouched). Active `strategy_param` rows are copied alongside. Idempotent. Orchestrator authenticates as the agent user via `service_account` in prod (set `ORCH_RESEARCH_ACCOUNT_ID` to the pinned account UUID).
- **V55** — Adds `account_strategy.use_risk_based_sizing` (BOOLEAN, default FALSE) + `risk_pct` (NUMERIC(5,4), default 0.0500, CHECK 0 < x ≤ 0.20). Lets legacy strategies (LSR/VCB/VBO/FundingCarry) opt into risk-based sizing via `StrategyHelper.calculateRiskBasedNotional`; `capital_allocation_pct` becomes the notional cap when the toggle is ON. Existing rows default to FALSE — live trading parity preserved until operator explicitly flips the toggle.
- **V56** — Backfills spec-engine rows (`strategy_code NOT IN ('LSR','LSR_V2','VCB','VBO','FCARRY')`) still at V55 defaults to `use_risk_based_sizing=TRUE` / `risk_pct=0.0200`, preserving their prior risk-based-2% behavior after engine code was rerouted through the unified `StrategyHelper` sizing helpers. Only touches rows still at V55 defaults — operator-customised rows are untouched.
- **V57** — Adds `backtest_run.strategy_risk_pcts` JSONB (map of `strategy_code → fractional risk_pct`, 0 < x ≤ 0.20). Per-run override parallel to `strategy_allocations`; null/missing key falls back to `account_strategy.risk_pct`. Fractional scale (e.g. 0.03), distinct from the existing `risk_per_trade_pct` scalar which is percent-scale (e.g. 2.0) for research-orchestrator compatibility.
- **V59** — Partial index `idx_backtest_run_active_per_user` on `backtest_run(user_id) WHERE status IN ('PENDING','RUNNING')` for the Kafka concurrency gate count query.
- **V58** — Adds `backtest_run.strategy_allow_long` and `strategy_allow_short` (nullable JSONB, map of `strategy_code → boolean`). Per-run per-strategy direction overrides; null/missing key falls back to `account_strategy.allow_long/allow_short`, then to run-level `allow_long/allow_short` for ad-hoc spec strategies. Lets operators research direction variants without permanently flipping a live `account_strategy` row.
- **V60** — Adds `backtest_run.avg_trade_return_pct` (NUMERIC(14,6)) + `geometric_return_pct_at_alloc_90` (NUMERIC(28,6)). Sizing-independent return metrics computed in `BacktestMetricsService` and `BacktestAnalysisService`. `avg_trade_return_pct` = mean of `(pnl / notional × 100)`; `geometric_return_pct_at_alloc_90` = compounded equity multiplier minus one, in percent, assuming every trade was sized at 90% of equity (clamps to ruin if a step would zero equity). Sit alongside the existing capital-based `return_pct` so a tiny notional × strong per-trade edge no longer hides in the headline. Wide 22-integer-digit precision on the geometric column intentional — a 1000-trade backtest with +5%/trade compounds to ~10^21 %; narrower precisions overflow `saveAndFlush` mid-run.
- **V61** — Flips research-agent (`account_id='99999999-9999-9999-9999-000000000002'`) `account_strategy` rows to `use_risk_based_sizing=FALSE` / `capital_allocation_pct=90.0000`. Scoped only to the research-agent account; admin's live LSR/VCB/VBO rows are untouched (different `account_id`). The protected-strategy hard rule stays intact. Idempotent (predicate skips already-normalised rows).
- **V62** — Toggleable risk gates. Adds three booleans on `account_strategy` (`kill_switch_gate_enabled`, `correlation_gate_enabled`, `concurrent_cap_gate_enabled`; `regime_gate_enabled` already exists from V43); all default FALSE and backfill FALSE — **deliberate live regression** so live behaviour matches what backtest has always done (no gates). Operator opts back in per strategy via PATCH. Adds four nullable JSONB cols on `backtest_run` (`strategy_kill_switch_overrides`, `strategy_regime_overrides`, `strategy_correlation_overrides`, `strategy_concurrent_cap_overrides`) — per-run per-strategy gate overrides, same shape as `strategy_allow_long`/`strategy_allow_short` (V58). `RiskGuardService.evaluate(EvaluationContext)` now runs the same gate stack in live (via the existing `canOpen` wrapper) and backtest (called from `BacktestCoordinatorService.tryFireEntry`/`manageOwnerActiveTrade`/`handleSingleStrategyStep` for every OPEN_* decision). Lot/min-notional remains live-only (exchange enforces). Closes Phase A parity audit findings 1/2/3/7. New audit action `STRATEGY_GATE_CONFIG_UPDATED` fires alongside `STRATEGY_UPDATED` whenever any gate toggle moves.
- **V63** — Rename exchange code BIN → BNC. In-place `UPDATE accounts SET exchange='BNC' WHERE exchange='BIN'`. `LiveTradingDecisionExecutorService` branches on `account.exchange == "BNC"`; historical rows + V54 research-agent seed used "BIN" so every live OPEN_LONG/OPEN_SHORT fell through to "Unsupported exchange" warn. Idempotent (rerun is no-op once all rows are BNC). Spot-only — futures support dropped.
- **V64** — Seed TEST strategy. Wires `ExecutionTestService` (strategy_code="TEST") to starsky / rfkih23 account in PAPER_TRADE across 4 intervals × 2 sides (8 rows). Covers all four exit structures (SINGLE, TP1_RUNNER, TP1_TP2_RUNNER, RUNNER_ONLY). Includes legacy `current_status='STOPPED'` in INSERT (column still exists at V64 apply time; dropped by V65). End-to-end execution validation only — never promoted to real capital.
- **V65** — Drop dead `account_strategy.current_status` column. Was set to 'STOPPED' on creation and never written with any other value by any service. Frontend dashboard derives LIVE/STOPPED from `enabled`, not from this column (Blackridge `mapAccountStrategy: status: (s.enabled ? 'LIVE' : 'STOPPED')`). Companion changes: `AccountStrategy.java` entity field removed, response DTO field removed, clone + revive paths cleaned. Idempotent via `DROP COLUMN IF EXISTS`.
- **V66** — ML/sentiment integration schema (Phase 1 / M1 of project_ml_blueprint.md). Adds 18 new tables across raw-data plane (`macro_raw`, `onchain_raw`, `news_raw`, `social_raw` — all monthly-partitioned on `event_time`), registries (`feature_registry`, `feature_compute_run`), model layer (`model_registry` with sha256 integrity + bootstrap_metrics + multi-interval training fields, `training_run`), signal layer (`signal_definition`, `signal_history` partitioned monthly on `ts`, `signal_health`), research workflow (`research_runs`, `reviews` — both NEW, not extensions), and promotion/audit (`model_promotion_gauntlet` with 13 gates + cooldown CHECK, `order_audit` partitioned monthly on `decided_at` retained forever, `ml_kill_switch_audit`, `reviewer_audit`, `researcher_override_budget` seeded with 2026-Q2 row). ALTERs `strategy_definition` (`ml_mode` OFF\|HYBRID default OFF + `ml_mode_shadow` default TRUE) and `account_strategy` (`per_symbol_exposure_cap_pct` default 1.5). Seeds `ML_DIRECTIONAL` row in `strategy_definition` (enabled=false, simulated=true — refuses to fire until operator opt-in). All new tables follow BaseEntity (4 audit cols: `created_time`, `created_by`, `updated_time`, `updated_by`) per operator directive overriding the historical market_data/feature_store exemption. Role grants: `blackheart_research` writes research-side tables (no DELETE on registries, no writes on live trading tables); `blackheart_trading` writes live tables (no writes on research-side). New strategy archetype `ML_DIRECTIONAL` joins LEGACY_JAVA + spec archetypes — distinct from existing strategies. Empty/seed at apply time; populated through Phase 1 M2-M3 ingestion + Phase 2+ training.
- **V68** — Rename `ml_source_health.*_24h` counters → `*_total`. Three columns: `rows_inserted_24h`→`rows_inserted_total`, `errors_24h`→`errors_total`, `rejected_pit_violations_24h`→`rejected_pit_violations_total`. The V67 names implied a 24-hour rolling window but the actual `update_source_health` logic increments unboundedly on each pull → counters represent cumulative lifetime, not 24h. Renaming for honesty. Safe to apply: only 7 rows existed at apply time (V67 seed), all at 0. Future migration may add separate `*_24h` columns with a true rolling-window implementation if dashboard needs them.
- **V67** — ML ingestion control plane (admin frontend integration, Phase 1 / M1+ of project_ml_blueprint.md). Adds two new tables: `ml_ingest_schedule` (admin-configurable per-source cron schedules; UNIQUE on `(source, symbol)`; JSONB `config` for source-specific params like FRED series IDs; Spring TaskScheduler reads this on 60s refresh tick and registers dynamic CronTriggers; admin edits via Blackridge → effective on next refresh) and `ml_source_health` (per-source health snapshot updated by every live-ingest tick; drives the Blackridge "ML Data Sources" dashboard; states: healthy/degraded/failed/disabled/unknown; tracks `consecutive_failures`, `rows_inserted_24h`, `errors_24h`, `rejected_pit_violations_24h`). Seeds 7 schedule rows (one per source — fred, binance_macro, defillama, coinmetrics, coingecko, alternative_me, forexfactory) all DISABLED — operator opts in per source via UI after the Python source module is implemented + smoke-tested. Seeds 7 health rows (one per source, status=unknown). Manual backfills reuse existing `historical_backfill_job` (V47) with new `JobType` enum values `BACKFILL_ML_FRED`, `BACKFILL_ML_BINANCE_MACRO`, etc. — no schema change for backfill; only new Java handler beans. Both new tables follow BaseEntity. Role grants: `blackheart_trading` writes both (live_ingest worker updates health, scheduler service writes last_run_at); `blackheart_research` reads both.

## Strategy Promotion Pipeline (V15 account-scope, V40 definition-scope)

```
RESEARCH    → enabled=false                  (research-mode only)
PAPER_TRADE → enabled=true,  simulated=true  (live signals, no real orders)
PROMOTED    → enabled=true,  simulated=false (real capital)
DEMOTED     → enabled=false, simulated=false
REJECTED    → enabled=false, simulated=false
```

Same 5-state graph + 7 legal transitions live at BOTH scopes (`chk_promotion_states`):
`RESEARCH→PAPER_TRADE`; `PAPER_TRADE→PROMOTED|REJECTED`; `PROMOTED→DEMOTED|PAPER_TRADE`; `DEMOTED→PAPER_TRADE`; `REJECTED→PAPER_TRADE`.

**V40 definition-scope (canonical for /research panel)**: lifecycle is now a property of the strategy itself. `strategy_definition.enabled`/`.simulated` columns are the source-of-truth for whether a strategy is paper/live globally; `strategy_promotion_log.strategy_definition_id` (nullable, exclusive with `account_strategy_id` per `chk_promotion_log_scope`) records definition-scope flips. Per-account `account_strategy.enabled`/`.simulated` remain as overrides.

**Live executor guardrail** (`LiveTradingDecisionExecutorService.execute()`): paper if EITHER `definition.simulated=true` OR `accountStrategy.simulated=true` — fail-safe direction. **Only OPEN_LONG/OPEN_SHORT diverted** to `paper_trade_run`; CLOSE_*/UPDATE_POSITION_MANAGEMENT always fall through to real execution. Critical invariant — emergency demote on a live position would otherwise strand it. Do not modify scope without re-reading bug-1 audit notes in `research/DEPLOYMENT.md`.

**Definition kill-switch**: `StrategyExecutorFactory.getIfDefinitionEnabled(code)` returns empty when `definition.enabled=false`, skipping the strategy for ALL accounts. The live coordinator uses this; backtest's `factory.get()` path is unchanged so historical research can still run on disabled strategies.

**Operator interface**:
- Account-scope (back-compat): `POST /api/v1/strategy-promotion/{accountStrategyId}/promote`.
- Definition-scope (V40, what /research uses): `POST /api/v1/strategy-promotion/definition/{strategyCode}/promote` `{toState, reason, evidence}`.

Both atomic — flip+log row in same `@Transactional`, pessimistic write lock prevents races. See `StrategyPromotionService.promote` / `.promoteDefinition`.

**New strategies default `simulated=true`** (`AccountStrategyService.create()` for accounts; column default for `strategy_definition`). Direct UPDATE on `simulated` bypasses audit trail; operators must use the controller.
