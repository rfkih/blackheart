# Flyway migration history

> Detailed change-log of every Flyway migration. Loaded on demand —
> the top-level `CLAUDE.md` only points to "current head" so the hot
> context doesn't carry the full archive.

## Conventions

- **Source of truth:** `src/main/resources/db/flyway/V<N>__name.sql`. Convention `V<n>__<verb>_<noun>.sql`.
- **Idempotency:** every migration uses `IF NOT EXISTS` / `IF EXISTS`. `ddl-auto=validate` stays on as safety net.
- **Baseline:** pre-Flyway state stamped V1 via `spring.flyway.baseline-on-migrate=true`. Legacy `db/migration/` is reference only.
- **Trading JVM owns Flyway.** Research JVM has Flyway disabled.

## Current head: V61

## V14–V61 catalog

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
