# Blackheart — Algorithmic Trading Platform

## Overview
Java-based algo trading platform; live trading + backtesting on Binance.

> **Future-state**: see `docs/PARAMETRIC_ENGINE_BLUEPRINT.md`. Hand-written Java strategies (LSR, VCB, VBO) are the proven legacy baseline and explicitly preserved.

## Tech Stack
- **Language/Framework**: Java 21, Spring Boot 3.3.5, Gradle
- **Data**: PostgreSQL (primary), Redis (cache/pub-sub), Kafka
- **Migrations**: Flyway (`db/flyway/V<N>__*.sql`); legacy `db/migration/*.sql` reference only
- **Exchange**: Binance REST + WebSocket; **TA**: TA4j; **Real-time**: WebSocket/STOMP
- **Auth**: JWT Bearer, Spring Security
- **Other**: Lombok, Jackson, Docker, Hibernate 6

## Architecture
Two parallel execution paths share a common strategy interface, **served by two separate JVMs (V14+)**.

### Deployment topology
```
Trading JVM (8080)                   Research JVM (8081)
─────────────────                    ───────────────────
BlackheartApplication                BlackheartResearchApplication
profile = dev (or prod)              profile = dev,research
Heap: -Xms2g -Xmx2g                  Heap: -Xms512m -Xmx1500m
tradingBootJar                       researchBootJar
Includes: live trading, market WS,   Includes: backtest, research,
schedulers, P&L publish              monte carlo, dev backdoor
Excludes: research/, backtest/,      Excludes: BinanceWebSocketClient,
montecarlo/, dev-tooling             schedulers, P&L publish, Telegram
(@Profile("research") AND            (all gated @Profile("!research"))
tradingBootJar exclusions)
            │                                       │
            └──────────► PostgreSQL ◄───────────────┘
```
Trading JVM never participates in research. Research JVM never connects to Binance. Communication is purely DB state. A research crash/OOM/restart cannot disturb live trading. See `research/DEPLOYMENT.md`.

### Live Trading Flow
```
Binance WS → FeatureStore → LiveOrchestratorCoordinatorService (multi-strategy fan-out)
→ LiveTradingCoordinatorService → DefaultStrategyContextEnrichmentService
→ StrategyExecutor → Decision → LiveTradingDecisionExecutorService
→ BinanceClientService → DB → LiveTradeListenerService → LivePnlPublisherService → WS
```

### Backtest Flow
```
BacktestService → BacktestCoordinatorService → MarketData/FeatureStore (DB)
→ StrategyExecutor → BacktestTradeExecutorService (fills/fees/slippage)
→ BacktestMetricsService → BacktestPersistenceService → BacktestEquityPointRecorder
```

### Multi-Strategy Orchestration
**Live** (`LiveOrchestratorCoordinatorService`) — invoked per `(account, interval)`:
- Sorted by `accountStrategy.priorityOrder` ascending.
- Entry phase: fan-out stops at first opener within interval-group.
- Active-trade phase: routes only to strategy whose `accountStrategyId` owns the open trade.
- Cap is **per-interval-group**: at most one active trade per `(account, interval)`. No global cap across intervals.

**Backtest** (`BacktestCoordinatorService`) — matches live exactly:
- Comma-separated `strategyCode` (e.g. `"LSR_V2,VCB"`); per-strategy params via `strategyAccountStrategyIds`.
- **Priority order**: sorted by persistent `accountStrategy.priorityOrder` asc (declaration-order tiebreak).
- **Per-interval-group cap**: `intervalGroupBusy` skips entry if any strategy on same interval has active trade or pending entry. Legacy `maxConcurrentStrategies` field on `BacktestRun` is no longer consulted.
- **Per-strategy bias data**: `preloadBiasDataPerStrategy` returns `Map<String, BiasData>` keyed by strategy code; cached by `biasInterval`.
- **Per-strategy pending entries**: `BacktestState.pendingEntriesByStrategy` (LinkedHashMap, deterministic). Concurrent same-bar entries from different strategies all queue and fill independently on next bar's open.
- **Trade `interval` stamping**: each `BacktestTrade`+`BacktestTradePosition` carries strategy's resolved interval, not run's primary.
- **Same-bar re-entry**: allowed (matches live). Strategy step always runs after listener step.

### Backtest Interval Restrictions
Engine ticks on `5m` monitor candle (`BacktestCoordinatorService.MONITOR_INTERVAL`). Strategy intervals restricted to **5m / 15m / 1h / 4h** at submit (`BacktestRunRequest.@Pattern`). Live trading still supports full range — restriction is backtest-only.

### Data Availability
**`BTCUSDT` and `ETHUSDT` both plumbed for backtest/research** (Phase 3 shipped 2026-05-01). `MarketData`+`FeatureStore` populated for both on all four intervals, ~17 months minimum. `BacktestRunRequest.asset` is free-form.

**Live trading is still single-symbol.** `BinanceWebSocketClient` parameterized via `app.live.symbol` (default `BTCUSDT`) but only one bean wired. Multi-symbol live needs multiple client beans OR list-typed property + per-symbol stream handlers, then trading-JVM restart.

**Other symbols (`SOLUSDT`, etc.):** NOT plumbed. Run historical backfill (`MarketDataService`/`HistoricalDataService`) first.

## Key Modules
| Module | Location | Key Classes |
|---|---|---|
| Strategy execution | `service/strategy/` | `StrategyExecutor`, `StrategyExecutorFactory`, `DefaultStrategyContextEnrichmentService`, `StrategyHelper` |
| Strategy impls | `service/strategy/` | `LsrStrategyService`, `VcbStrategyService`, `ExecutionTestService` |
| Strategy params | `service/strategy/` | `LsrStrategyParamService`, `VcbStrategyParamService`, `VboStrategyParamService` (legacy); `StrategyParamService` (unified, **spec-driven only**, M1+); `AccountStrategyService` |
| Live trading | `service/live/` | `LiveOrchestratorCoordinatorService`, `LiveTradingCoordinatorService`, `LiveTradingDecisionExecutorService`, `LiveTradeListenerService`, `LivePositionSnapshotMapper` |
| Backtest | `service/backtest/` | `BacktestCoordinatorService`, `BacktestTradeExecutorService`, `BacktestMetricsService`, `BacktestPersistenceService`, `BacktestStateService`, `BacktestEquityPointRecorder` |
| Trade mgmt | `service/trade/` | `TradeOpenService`, `TradeCloseService`, `TradeStateSyncService` |
| Market data | `service/marketdata/`, `stream/` | `BinanceWebSocketClient`, `MarketDataService`, `HistoricalDataService` |
| Clients | `client/` | `BinanceClientService`, `TokocryptoClientService`, `DeepLearningClientService` |
| Portfolio | `service/portfolio/` | `PortfolioService` |
| User/Auth | `service/user/` | `UserService`, `JwtService`, `AccountQueryService` |
| Audit | `service/audit/` | `AuditService` — service-layer, transaction-bound, never throws |

## Key Models
| Model | Purpose |
|---|---|
| `User` | Platform user (email/password, JWT) |
| `Account` | Exchange API account; `userId` FK. Risk: `maxConcurrentLongs/Shorts/Trades` (null total = no cap), `volTargetingEnabled`, `bookVolTargetPct` |
| `AccountStrategy` | Account→strategy link. Soft-delete via `is_deleted`+`deleted_at` (preserves FK for historical trades). PATCH: `intervalName` (blocked if open trades), `priorityOrder` (1-99) |
| `AuditEvent` | Append-only log. Fields: `actorUserId`, `action`, `entityType`, `entityId`, `beforeData`/`afterData` (JSONB), `reason`, `createdAt`. Transaction-bound. Indexed by actor, (entity_type,entity_id), created_at |
| `LsrStrategyParam` / `VcbStrategyParam` / `VboStrategyParam` | Per-account-strategy overrides; JSONB `param_overrides`, `@Version` optimistic lock |
| `StrategyParam` | Unified overrides for **spec-driven strategies only** (M1+). JSONB `param_overrides`. Legacy LSR/VCB/VBO keep their own tables. |
| `StrategyDefinition` | Registry; `archetype` (`LEGACY_JAVA` for hand-coded), `archetypeVersion`, `specJsonb`, `isDeleted`/`deletedAt` |
| `StrategyDefinitionHistory` | Append-only spec mutation log (INSERT/UPDATE/DELETE/UPGRADE) |
| `SpecTrace` | Per-evaluation engine decision trace (backtest dense, live 1% sample). Drives `SpecTraceViewer` + engine kill-switch |
| `Trades` | Parent record (status: OPEN / PARTIALLY_CLOSED / CLOSED) |
| `TradePosition` | Child leg: SINGLE, TP1, TP2, RUNNER |
| `MarketData` | OHLCV |
| `FeatureStore` | Pre-computed indicators (EMA, ADX, RSI, MACD, ATR, BB, KC, Donchian, relVol, ER, signedER, etc.) |
| `BacktestRun` | Backtest config+result; `strategyAccountStrategyIds` JSONB for per-strategy param mapping |
| `BacktestTrade` / `BacktestTradePosition` | Simulated trade records |
| `BacktestEquityPoint` | Per-candle equity snapshots |

## Active Strategies
| Code | Class | Status | Description |
|---|---|---|---|
| `LSR` | `LsrStrategyService` | **Production — profitable** | Long/Short reversal; `LsrParams` |
| `VCB` | `VcbStrategyService` | **Production — profitable** | Volatility Compression Breakout v2.1; `VcbParams` |
| `VBO` | `VolatilityBreakoutStrategyService` | **Production — profitable** | Volatility Breakout (BB-width compression→expansion); `VboParams` |
| `TPR` | `TrendPullbackStrategyService` | Research / not yet profitable | Trend-following w/ pullback entries; position-mgmt exits |
| `TEST` | `ExecutionTestService` | Execution-only | Testing harness — not for live |

> Strategy codes are the literal `STRATEGY_CODE` constant. Legacy `LSR_V2`/`TREND_PULLBACK_SINGLE_EXIT`/`RAHT_V1`/`TSMOM_V1`/`DCT` are no longer registered — do not seed `account_strategy.strategy_code` with them.
>
> Production status reflects live P&L. LSR/VCB/VBO are the proven baseline; TPR is a tuning candidate, not a benchmark.
>
> **Discarded:** `DCT` (Donchian, 4h, 2026-04-27 — peaked ~10%/yr, no margin; V8 drops `dct_strategy_param`). `BBR` (Bollinger Reversal, 1h) and `CMR` (Chop Mean Reversion, 15m) removed 2026-04-30 — BBR=NO_EDGE, CMR never traded. Java executors+factory wiring deleted; `mean_reversion_oscillator` archetype template stays. Lesson: a strategy that *just* clears 10%/yr isn't worth a slot when existing book is +20%+ each.

## Account Strategy Lifecycle
- **Create** `POST /api/v1/account-strategies` — verifies user owns account, `strategyCode` resolves. Defaults: `enabled=false`, `currentStatus="STOPPED"`, `is_deleted=false`. Emits `STRATEGY_CREATED`.
- **Activate/Deactivate** `POST /:id/activate`|`/deactivate` — flips `enabled`. Activate atomically deactivates sibling on same `(account, strategyDefinition, symbol, interval)`, blocking if sibling has open trades. Emits `STRATEGY_ACTIVATED`/`DEACTIVATED`.
- **Update** `PATCH /:id` — `intervalName` blocked if open trades; `priorityOrder` (1-99) no guard. Emits `STRATEGY_UPDATED` w/ before/after.
- **Soft-delete** `DELETE /:id` — sets `is_deleted=true`, `enabled=false`, `deleted_at=now()`. Blocked if `countOpenByAccountStrategyId>0` (throws `IllegalStateException`). Historical trades still resolve. Emits `STRATEGY_DELETED`.
- **Re-arm kill switch** `POST /:id/rearm` — clears DD trip. Emits `KILL_SWITCH_REARMED`.
- **Liveness flag is `enabled`, not `currentStatus`**: `current_status` is legacy, no service writes it. All active queries filter `enabled=true AND is_deleted=false`. Frontend status badge derived from `enabled`.
- **`is_deleted` filter**: every `AccountStrategyRepository` query filters it (live orchestration, scheduler, UI list). Trade/P&L joins must **NOT** filter `is_deleted` — historical attribution depends on resolving deleted strategies.

## Per-Strategy Parameter System (LSR + VCB + VBO)
- **Storage**: `lsr_strategy_param`/`vcb_strategy_param`/`vbo_strategy_param` — JSONB `param_overrides`, `@JdbcTypeCode(SqlTypes.JSON)`, `@Version` optimistic lock.
- **Param objects**: `LsrParams`/`VcbParams`/`VboParams` — `@Builder.Default`, `defaults()` factory, `merge(Map)` overlay. `VboParams` adds `applyOverrides(Map)`; merge handles boolean gates (`requireKcSqueeze`, `requireDonchianBreak`, `requireTrendAlignment`).
- **Cache**: Redis, `GenericJackson2JsonRedisSerializer`; prefix `lsr:params:`/`vcb:params:`/`vbo:params:`; TTL 1h. Evicted **after commit** via `TransactionSynchronizationManager.afterCommit()`.
- **Concurrent insert**: `putParams`/`patchParams` catch `DataIntegrityViolationException`, retry UPDATE.
- **REST**: `/api/v1/lsr-params/{id}`, `/vcb-params/{id}`, `/vbo-params/{id}` — GET/PUT/PATCH/DELETE + GET `/defaults`.
- **Backtest integration**: `BacktestRun.strategyAccountStrategyIds` JSONB `Map<String,UUID>` (strategy code → accountStrategyId); falls back to global `accountStrategyId`.

## Context Enrichment (Live vs Backtest)
`DefaultStrategyContextEnrichmentService` builds `BaseStrategyContext`→`EnrichedStrategyContext`:
- **Bias candle (live)**: last **completed** via `end_time < now()` — never forming candle.
- **previousFeatureStore (live)**: `findPreviousBySymbolIntervalAndStartTime` when `requirements.isRequirePreviousFeatureStore()=true`. **Critical for compression-breakout** (VCB gate 2 checks previous bar compression).
- **Backtest**: bias and previousFeatureStore set manually by `BacktestCoordinatorService`; enrichment skips live-only DB queries when `executionMetadata.source=="backtest"`.

## Design Patterns
Strategy (`StrategyExecutor`); Factory (`StrategyExecutorFactory` registry); Coordinator (multi-service flows); Context Object (`EnrichedStrategyContext`); Feature Pre-computation (`FeatureStore`); Multi-leg Exits (TP1/TP2/RUNNER); Param Override (JSONB merged over defaults; null-safe cache-aside w/ post-commit eviction).

## API Endpoints
| Controller | Base Path | Purpose |
|---|---|---|
| `UserController` | `/api/v1/users` | Register, login, profile (GET/PATCH `/me`) |
| `AccountStrategyController` | `/api/v1/account-strategies` | CRUD; `PATCH /:id` accepts `intervalName`+`priorityOrder`; `POST /:id/activate`/`/deactivate`/`/rearm`; `DELETE /:id` soft-delete (blocked if OPEN/PARTIALLY_CLOSED). Mutations emit audit events |
| `AccountController` | `/api/v1/accounts` | `PATCH /:id/risk-config` (`maxConcurrent*`, `volTargeting*`; null = unchanged; `<1` clears total cap); `PATCH /:id/credentials` rotates Binance key/secret via `EncryptedStringConverter` |
| `LsrStrategyParamController` | `/api/v1/lsr-params` | LSR param CRUD |
| `VcbStrategyParamController` | `/api/v1/vcb-params` | VCB param CRUD |
| `VboStrategyParamController` | `/api/v1/vbo-params` | VBO param CRUD |
| `BacktestController` | `/api/v1/backtest` | Submit/query backtests |
| `TradeController` / `TradeQueryController` | `/api/v1/trades` | Trade mgmt + queries |
| `TradePnlQueryController` | `/api/v1/pnl` | P&L queries |
| `PortfolioController` | `/api/v1/portfolio` | Balances. `?accountId=<uuid>` scopes single owned account (else `AccessDeniedException`). Omit `accountId` to aggregate all owned: free/locked summed per asset, USDT recomputed once on merged total (no double-applied spot price). |
| `PortofolioController` | `/api/v1/portofolio` (alias `/v1/portofolio`) | **Admin-only** legacy reload (`GET /reload`). Typo preserved for back-compat. Distinct from `PortfolioController`. |
| `MarketQueryController` | `/api/v1/market` | Market data |
| `SchedulerController` | `/api/v1/scheduler` | Scheduler. `IP_MONITOR` calls `IpMonitorService.checkAndNotifyIfChanged()` |
| `MonteCarloController` | `/api/v1/montecarlo` | Monte Carlo |
| `ResearchQueueController` | `/api/v1/research/queue` | **Mixed**. Admin: full CRUD (`@PreAuthorize("hasRole('ADMIN')")`). User: `POST /me` (`created_by=jwtUserId`). Orchestrator processes both identically. |
| `ResearchController` | `/api/v1/research` | **Mixed** (no class-level `@PreAuthorize`). Sweeps + `GET /tpr/params` user-accessible w/ service ownership check (rejects `state.getUserId()`≠JWT). Admin via method-level: `GET /backtest/:id/analysis`, `PUT /tpr/params`, `POST /tpr/params/reset`, `GET /log`. New sweep-adjacent: no `@PreAuthorize`, use service ownership check. New global/mutating: `@PreAuthorize("hasRole('ADMIN')")`. |
| `ServerInfoController` | `/api/v1/server` | `GET /ip` ipify live (broker-setup). `GET /ip/status` returns latest `ServerIpLog` written by `IP_MONITOR`. Frontend `IpWhitelistBanner` polls; warns on `event=="CHANGED"`. Don't make `/ip/status` call ipify — point is cheap polling. |
| `ErrorLogController` | `/api/v1/error-log` | **Admin-only.** Fingerprint-deduped inbox. Paged list, `GET /{id}` detail (full stack + redacted MDC), `GET /open-count?minSeverity=`, `PATCH /{id}/status` (NEW/INVESTIGATING/RESOLVED/IGNORED/WONT_FIX). Reopen pre-checks partial-unique-index on `(fingerprint WHERE status IN NEW/INVESTIGATING)`, 409s if fresh open. MDC redacts `authorization/password/token/secret/apikey/cookie/x-*`. |
| `SpecTraceController` | `/api/v1/spec-trace` | **Admin, `@Profile("!research")`.** V19 viewer. List **requires** `backtestRunId`/`accountStrategyId` (else full-table scan). List omits heavy `specSnapshot`; `GET /{id}` includes it + per-rule trace. Filters trimmed+uppercased server-side. |
| `StrategyDefinitionHistoryController` | `/api/v1/strategy-definition-history` | **Admin, `@Profile("!research")`.** V18 viewer. List threads `priorHistoryId` across pagination. `GET /{id}` returns full `specJsonb`. Writes owned by `StrategyDefinitionHistoryService`. |

## Security
- JWT Bearer; `JwtService` issues+validates.
- Public: `/healthcheck`, `/ws`, `/ws/**`, `/api/v1/users/register`, `/api/v1/users/login`. All else: `Authorization: Bearer <token>`.
- `jwtService.extractUserId(token)` resolves caller UUID for ownership checks.
- **JWT secret**: `app.jwt.secret=${JWT_SECRET:<dev-sentinel>}`. `JwtService.validateSecretOnStartup` refuses boot if sentinel used + active profile is not `dev`/`test`/`local`. Generate prod secret via `id.co.blackheart.util.JwtSecretGenerator`.
- **Audit log**: every security-sensitive mutation writes `audit_event` in same transaction as mutation. Service-layer `AuditService.record(...)` — never AOP. Failures logged WARN, never propagate.

## Strategy Promotion Pipeline (V15+)
```
RESEARCH    → enabled=false                  (research-mode only)
PAPER_TRADE → enabled=true,  simulated=true  (live signals, no real orders)
PROMOTED    → enabled=true,  simulated=false (real capital)
DEMOTED     → enabled=false, simulated=false
REJECTED    → enabled=false, simulated=false
```
DB-enforced transitions (`chk_promotion_states`): `RESEARCH→PAPER_TRADE`; `PAPER_TRADE→PROMOTED|REJECTED`; `PROMOTED→DEMOTED|PAPER_TRADE`; `DEMOTED→PAPER_TRADE`; `REJECTED→PAPER_TRADE`.

**Live executor guardrail** (`LiveTradingDecisionExecutorService.execute()`): when `accountStrategy.simulated=true`, **only OPEN_LONG/OPEN_SHORT diverted** to `paper_trade_run`. CLOSE_*/UPDATE_POSITION_MANAGEMENT always fall through to real execution — otherwise emergency demote on a live position would strand it. Critical invariant; do not modify scope without re-reading bug-3 audit notes in `research/DEPLOYMENT.md`.

**Operator interface**: `POST /api/v1/strategy-promotion/{id}/promote` `{toState, reason, evidence}`. Atomic — flip+log row in same `@Transactional`. Pessimistic write lock prevents races. See `StrategyPromotionService`.

**New strategies default `simulated=true`** (`AccountStrategyService.create()`). Direct UPDATE on `simulated` bypasses audit trail; operators must use the controller.

## Strategy Spec Language (Phase 2)
YAML specs codegen into Java. Editing YAML safer than Java. Templates in `research/templates/`: 4 archetypes (`mean_reversion_oscillator`, `trend_pullback`, `donchian_breakout`, `momentum_mean_reversion`) + `_common_helpers.java.tmpl` + `_common_params_helpers.java.tmpl`.

Pipeline: `research/specs/<code>.yml` → `codegen-strategy.py --validate --update-factory --check` → `deploy-from-spec.sh` (spec→codegen→factory wire→compile→restart→seed→queue).

```bash
python3 research/scripts/codegen-strategy.py --spec research/specs/your.yml --validate
bash research/scripts/deploy-from-spec.sh --spec research/specs/your.yml --interval 1h \
    --sweep '{"params":[{"name":"PARAM","values":[v1,v2,v3,v4]}]}' --iter-budget 4
```
Schema: `research/specs/SCHEMA.md`. Validator type-checks defaults, archetype-specific entry, version. Codegen ~290-line classes via `_common_helpers`. **Layer 2 autonomous gen** (CronCreate armed): agent picks unused archetype, writes YAML, runs `deploy-from-spec.sh`. **4h frequency cap** (`/tmp/last-strategy-gen.txt`). Compile-failure rollback via `deploy-strategy.sh` factory-restore.

## External Services
**Binance** primary, **Tokocrypto** secondary. **FastAPI** :8000 ML/DL. **Node.js** :3000.

## Working Rules

### General
Minimal, targeted changes over large refactors. Don't change public API contracts, rename entities/DTO fields/DB columns unless requested. Schema changes always include migration SQL. Preserve back-compat whenever possible.

### Trading Logic Safety
Don't simplify execution/fill/fee/P&L without explaining consequences. Preserve live↔backtest consistency unless divergence is intentional+documented. Respect Binance constraints (lot size, precision, min notional, balance). When changing SL/TP/trailing/runner: explain what changed, why, lifecycle impact, parity impact.

### Live / Backtest Parity (audited 2026-04-26)
- `DefaultStrategyContextEnrichmentService` skips live DB queries when `source=="backtest"` — don't break this guard.
- `previousFeatureStore` must populate when `requirePreviousFeatureStore=true`; live from DB, backtest manual.
- Live bias must use `findLatestCompletedBySymbolAndInterval` (completed), not `findLatestBySymbolAndInterval` (may return forming).
- **Cap is per-interval-group**, not global. `intervalGroupBusy` mirrors live's "first opener wins per `(account,interval)`". Don't reintroduce a global active-trade count for entry gating.
- **Bias data is per-strategy**. `preloadBiasDataPerStrategy` returns one `BiasData` per strategy code from its own `requirements.biasInterval`. Don't collapse back to single merged series.
- **Priority order matches live**: `resolveStrategyExecutors` sorts by `accountStrategy.priorityOrder` asc (declaration-order tiebreak). Don't reintroduce request-declaration-order as primary sort.
- **Same-bar re-entry allowed**. `handleStrategyStep` runs unconditionally after `handleListenerStep`; don't gate on `anyPositionClosed` or aggregate state. Live has no such gate.
- **Per-strategy pending entries**: `BacktestState.pendingEntriesByStrategy` keyed by uppercase strategy code (LinkedHashMap, deterministic).
- **Trade `interval` is strategy's resolved interval**, not `backtestRun.getInterval()`. Pending entry carries it; `openTrade` stamps trade+positions.

### Point-in-Time Discipline (audited 2026-04-26)
- Strategy execution gates on `BinanceWebSocketClient.isProcessable` (Binance `k.x` closed-candle flag) — entries never see forming bar.
- Bias-TF enrichment: `findLatestCompletedBySymbolAndInterval(boundary=now)` w/ `start_time < now` — completed-only.
- `FeatureStoreRepository.findLatestBySymbolAndInterval` (no completed filter) is **decision-unsafe** — only used by `SentimentPublisherService` (informational). Javadoc warns; honor it. Add a completed-filter variant if a new caller emerges.
- Rule: anything influencing entry/exit price level OR sizing must read completed-only. Anything informational (UI/alerts) can use latest-including-forming.
- Live entry sizing in `LiveTradingDecisionExecutorService`: `executeOpenLong` reads `decision.getNotionalSize()` (USDT, checked vs USDT balance); `executeOpenShort` reads `decision.getPositionSize()` (BTC qty, checked vs BTC balance). Wrong field/currency → executor silently falls back to its own sizing. For SHORT use `StrategyHelper.calculateShortPositionSize` (BTC), **not** `calculateEntryNotional(SIDE_SHORT)` (returns USDT, always fails BTC guard).
- `buildPositionSnapshot` in `LiveTradingCoordinatorService` returns `hasOpenPosition=false` when no OPEN `TradePosition` rows exist, regardless of parent `Trades` status.

### Code Change Format
1. Root cause/objective. 2. Proposed solution. 3. Files/classes affected. 4. Risks/edge cases. 5. Code changes. 6. Test checklist.

### Database / Persistence
- Avoid unnecessary writes in hot paths.
- Use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB in Hibernate 6 — NOT `AttributeConverter<Map,String>`.
- Backtest persistence stays efficient; no heavy writes inside execution loop unless required.
- Prefer daily equity persistence over per-candle unless requested.

### Migration Strategy
- **Flyway** is source of truth: `src/main/resources/db/flyway/V<N>__name.sql`. Convention: `V<n>__<verb>_<noun>.sql`.
- **Current head: V39.** Recent: V14 db role separation (`blackheart_trading` full DML; `blackheart_research` SELECT operational + DML backtest/research/promotion; both NOLOGIN; see `research/DB_USER_SEPARATION.md`). V15 promotion pipeline (`account_strategy.simulated`, `paper_trade_run`, `strategy_promotion_log` w/ CHECK). V16 unified `strategy_param` (1:1, spec-driven only). V17 `strategy_definition` spec columns. V18 `strategy_definition_history`. V19 `spec_trace` (backtest dense, live 1% sample). V20/V21 Postgres LISTEN/NOTIFY for spec mutations. V22 funding-rate cols on `backtest_run`. V23 `research_control` (kill-switch+global flags). V24/V25 `error_log` + grant to research role. V26 `code_review_finding`. V27 `scheduler_job_last_run`. V28 `idempotency_record` for FastAPI orchestrator (TTL ~24h). V29 redesigns `strategy_param` to 1:N saved presets (PK now `param_id`; `is_active` flags the live preset; soft-deleted presets stay resolvable for backtest reproducibility). V30 backfills `strategy_param` from legacy `lsr_/vcb_/vbo_strategy_param` + seeds an empty `default` preset for every account_strategy w/o one. V31 `backtest_run.strategy_param_ids` JSONB pins specific preset rows by `param_id` (incl. soft-deleted) so analysis doesn't drift when active preset changes. V32 `backtest_run.triggered_by` (`USER`/`RESEARCHER` tag for frontend filtering; not a security control). V33 `strategy_param.source_backtest_run_id` (preset provenance; ON DELETE SET NULL). V34 `funding_rate_history` (Phase 4 — per-symbol 8h Binance funding events; PK `(symbol, funding_time)` idempotent). V35 `feature_store` funding cols (`funding_rate_8h`, `funding_rate_7d_avg`, `funding_rate_z`). V36 seeds FCARRY strategy_definition (`LEGACY_JAVA` archetype → `FundingCarryStrategyService`). V37 seeds FUNDING_INGEST scheduler (8h cron, symbols from `app.funding.symbols`). V38 `cross_window_run` (regime-labeled epochs; ROBUST_CROSS_WINDOW = ≥80% windows net-positive after +20bps slippage; window defs in `research-orchestrator/config/regime_windows.yml`). V39 `alert_event` append-only log for `AlertService.raise()` (Phase 7.1; logged even when dedup suppresses outbound).
- **Baseline**: pre-Flyway state stamped V1 via `spring.flyway.baseline-on-migrate=true`. Legacy `db/migration/` reference only.
- **Idempotency**: every migration uses `IF NOT EXISTS`/`IF EXISTS`. **`ddl-auto=validate`** stays on as safety net. **Research JVM has Flyway disabled** — trading JVM owns schema.

### Logging / Observability
Clear logs for strategy decisions, order execution, fills, stop updates, reconciliation. No noisy logs in high-frequency paths.

### Architecture Discipline
Strategy logic in strategy modules; orchestration in coordinators; exchange-specific in client/services. Don't move business logic into controllers. Strategy param services follow a fixed pattern — reuse it for new parameterized strategies.

### If Uncertain
Ask: minimal patch or structural refactor? For risky changes, explain first.

## Common Commands

### Build
```bash
./gradlew build                    # full build incl. tests
./gradlew compileJava              # fast Java-only compile
./gradlew tradingBootJar           # build/libs/blackheart-trading-*.jar (excludes research)
./gradlew researchBootJar          # build/libs/blackheart-research-*.jar (incl. dev-tooling)
./gradlew bootRun                  # dev-mode :8080 (DEPRECATED — use JAR)
```

### Run (production-grade two-JVM)
```bash
# Trading JVM (8080, long-lived)
java -Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
    -jar build/libs/blackheart-trading-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=dev --server.port=8080

# Research JVM (8081, restart-safe)
java -Xms512m -Xmx1500m \
    -jar build/libs/blackheart-research-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=dev,research --server.port=8081

# Or via launchers:
bash research/scripts/run-trading-service.sh --background
bash research/scripts/run-research-service.sh --background
bash research/scripts/watch-research-jvm.sh &  # research-JVM auto-restart watcher
```

### Research operations
**Primary path: research-orchestrator HTTP API** (port 8082, loopback). Agents+frontend use this; bash scripts are operator fallback.
```bash
# HTTP API (X-Orch-Token + X-Agent-Name headers)
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" http://127.0.0.1:8082/agent/state
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" 'http://127.0.0.1:8082/leaderboard?limit=15'

# Tick (claim → submit → poll → analyse → write)
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: tick-$(date +%s)" http://127.0.0.1:8082/tick

# Walk-forward (after SIGNIFICANT_EDGE parks queue)
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: walk-$(date +%s)" \
     -d '{"queue_id":"...","n_folds":6}' http://127.0.0.1:8082/walk-forward

# Queue (agent boundary — orch token)
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: q-$(date +%s)" \
     -d '{"strategyCode":"LSR","intervalName":"1h","sweepConfig":{...},"iterBudget":4}' \
     http://127.0.0.1:8082/queue
# Queue (frontend/user — JWT): POST /api/v1/research/queue/me

# Phase 2 spec workflow (still bash-driven)
python3 research/scripts/codegen-strategy.py --spec research/specs/<code>.yml --validate
bash research/scripts/deploy-from-spec.sh --spec research/specs/<code>.yml --interval 1h --sweep '...' --iter-budget 4

# Fallback bash (operator-only; do not script agents against these)
bash research/scripts/research-tick.sh
bash research/scripts/run-continuous.sh --hours 24
bash research/scripts/leaderboard.sh --top 15
bash research/scripts/queue-strategy.sh --code LSR --interval 1h --hypothesis "..." --sweep '...' --iter-budget N
bash research/scripts/reconstruct-strategy.sh <iter_id>
bash research/scripts/burn-queue-load.sh
```

### Test
```bash
./gradlew test
./gradlew test --tests "com.example.YourTest"
```

## Domain Invariants
- `Trades` is parent; `TradePosition` is child leg (SINGLE/TP1/TP2/RUNNER).
- Parent/child consistency preserved after every open/close/update.
- `FeatureStore` is preferred precomputed-indicator source.
- Live and backtest share strategy logic via common interface.
- A `PARTIALLY_CLOSED` parent w/ no OPEN children is not tradable — don't route into position management.
- Per-strategy params fall back to `defaults()` when no override row or `accountStrategyId` null.

## Do Not
- Don't rewrite architecture unless requested.
- Don't replace TA4j-based logic without justification.
- Don't bypass risk checks, fee handling, or fill simulation.
- Don't introduce hidden behavior changes in strategy execution.
- Don't make broad package moves or rename classes unnecessarily.
- Don't use `@Convert(converter = JsonMapConverter.class)` for JSONB — use `@JdbcTypeCode(SqlTypes.JSON)`.
- Don't evict Redis cache inside `@Transactional` — use `afterCommit()` via `TransactionSynchronizationManager`.
- **Don't modify scope of `LiveTradingDecisionExecutorService`'s `simulated` check.** Diverts only `OPEN_LONG`/`OPEN_SHORT`. Expanding to CLOSE_*/UPDATE strands real positions on demote. (Phase 1 audit Bug 1.)
- **Don't remove `@Profile("!research")` from `BlackheartApplication`** or `@Profile("research")` from `BlackheartResearchApplication`. Both required to prevent JpaRepositories collision.
- **Don't remove `tr -d '[:space:]'`** from `research-tick.sh` psql captures — strips Windows CR; without it backtest submission fails HTTP 500 (`JsonParseException: Illegal CTRL-CHAR code 13`).
- **Don't bypass `deploy-strategy.sh`'s 4-hour cap** by overriding `STRATEGY_GEN_MIN_INTERVAL_SECONDS`. Prevents restart thrashing on trading JVM.

## Operations Telemetry (V23+)
Both JVMs expose Spring Boot Actuator + Micrometer Prometheus for `/research` dashboard.

### Endpoints (all admin-only)
- `GET /actuator/health` — liveness. Frontend service-health panel polls (admin session).
- `GET /actuator/info` — build/git.
- `GET /actuator/metrics` / `/{name}` — e.g. `jvm.memory.used`, `jvm.gc.pause`, `process.uptime`, `system.cpu.usage`, `process.cpu.usage`, `jvm.threads.live`.
- `GET /actuator/prometheus` — full scrape; reserved for future Prometheus pull.

Admin-gated because actuator surfaces internal state (heap addresses, thread dumps). Both JVMs route through same `SecurityConfig`.

### Strategy Promotion endpoints (admin-only) — `StrategyPromotionController` `/api/v1/strategy-promotion/*`
- `POST /{id}/promote` — atomic flip+log (uses `StrategyPromotionService`).
- `GET /{id}/state` — derived from `enabled`+`simulated`.
- `GET /{id}/history` — promotion log.
- `GET /{id}/paper-trades` — paper-trade run rows.
- `GET /recent?limit=50` — V23+, cross-strategy feed for "Recent promotions" panel.

`AccountStrategy` exposes `simulated` on wire so frontend can derive state (RESEARCH/PAPER_TRADE/PROMOTED/DEMOTED). Without `simulated`, dashboard can't distinguish PROMOTED from DEMOTED.

## Research Orchestrator (FastAPI, V28+)
Separate Python/FastAPI service in `research-orchestrator/` (port 8082) — **agent-facing front door**. Replaces bash drivers (`research-tick.sh`, `analyze-run.py`, `walk-forward.sh`, `queue-strategy.sh`) with typed, transactional HTTP API. Bash remains operator fallback only.

### Topology
```
quant-researcher agent / cron / dashboard
            │   X-Orch-Token + X-Agent-Name [+ Idempotency-Key]
            ▼
research-orchestrator (uvicorn, single worker, 127.0.0.1:8082)
   │ asyncpg as blackheart_research                 │ httpx
   ▼                                                 ▼
PostgreSQL (V28 idempotency_record, V13 research_queue)   Research JVM (8081)
```

### What it owns
- **Queue claim** — `SELECT … FOR UPDATE SKIP LOCKED` on `research_queue`; stuck-row reaper for crashed ticks.
- **Backtest submit+poll** — `POST /backtest` to research JVM, polls `backtest_run.status` to terminal.
- **Statistical analysis** — bootstrap 95% CI on profit factor, PSR (Bailey & López de Prado 2014), slippage haircut +5/+10/+20/+50bps, regime stratification.
- **Walk-forward** — 6-fold rolling window, verdict ∈ {ROBUST, INCONSISTENT, INSUFFICIENT_EVIDENCE, OVERFIT, NO_EDGE}. ROBUST is the gate for graduation.
- **Idempotent retries** — `Idempotency-Key` dedupes across restarts via Postgres `idempotency_record` (V28).

### Endpoints (agent contract)
| Method | Path | Purpose |
|---|---|---|
| GET  | `/healthz`, `/readyz` | Liveness/readiness (public) |
| GET  | `/agent/playbook` | Discoverable contract (public) |
| GET  | `/agent/state` | Snapshot (db_ok, jvm_ok, notes, next_actions) |
| GET  | `/queue` / `/queue/{id}` | Browse queue; cursor-paginated |
| POST | `/queue` | Enqueue sweep (Idempotency-Key honoured) |
| POST | `/queue/{id}/cancel` | Park PENDING/RUNNING with reason |
| GET  | `/iterations` / `/iterations/{id}` / `/leaderboard` | Read iteration_log |
| GET  | `/journal` / `/journal/{id}` | Read research_journal |
| POST | `/tick` | Full iteration end-to-end (~30 min sync) |
| POST | `/walk-forward` | 6-fold validation (~3 hours sync); after SIGNIFICANT_EDGE parks queue |

### Quant-grade gate (V11+ statistical contract)
Tick returns `verdict=PASS` only when **all**: n_trades≥100, PF 95% CI lower>1.0, +20bps slippage net PnL>0, statistical_verdict=SIGNIFICANT_EDGE. SIGNIFICANT_EDGE iterations park queue + emit `next_action` for `/walk-forward`; only graduation-eligible after `stability_verdict=ROBUST`.

### Operating notes
- **Loopback only.** `127.0.0.1`; shared-secret token = defense-in-depth.
- **Single uvicorn worker.** Claim loop+idempotency assume one process — scale by sharding queue, not workers.
- **Migrations live in trading JVM**, not orchestrator. Orchestrator is DML-only client. New tables → Flyway in `blackheart/src/main/resources/db/flyway/` (V28 example).
- **Profile=prod refuses dev secrets.** `Settings.assert_prod_safe` blocks startup w/ dev sentinel token or `dev_bypass` JVM auth.
- **Idempotency store** in-memory dev, Postgres prod (`PostgresIdempotencyStore` reads V28 `idempotency_record`).

## Autonomous Research Loop (agent contract)
quant-researcher operates **independently**. Standing goal: *find next profitable strategy* — net ≥10%/yr after fees+slippage, walk-forward `ROBUST`. Operator does NOT hand hypotheses.

**Agent decides**: what to research (reads `/leaderboard`+`/agent/state`+iteration_log for coverage gaps); sweep design (archetype/ranges/interval/budget); when to escalate (SIGNIFICANT_EDGE → `/walk-forward` next loop, no approval); when to abandon (NO_EDGE/MARGINAL → journal discarded, move on — don't keep tuning a dead strategy); journaling (pre-register *before* sweep — rationale, config, expected edge; then verdict; then escalate-or-abandon).

**Agent does NOT decide**: promotion to real capital (ROBUST = **gate, not trigger**; agent stops at "graduation candidate", operator runs promote); statistical thresholds (V11 gate fixed: n≥100, PF 95% CI lower>1.0, +20bps net positive, PSR≥0.95 — adjusting = moving goalposts); methodology constants (fold count, slippage grid, IS/OOS/holdout split — operator-controlled); anything outside `research-orchestrator/` and `research/specs/` (full out-of-bounds: `.claude/agents/quant-researcher.md`); protected strategies LSR/VCB/VBO + their param tables.

**Cadence**: CronCreate every N hours (default 6); each fire = one `/tick` if queue depth, else fresh hypothesis enqueue; SIGNIFICANT_EDGE → `/walk-forward` next fire; spec-gen cap = 4h min (`/tmp/last-strategy-gen.txt`).

**Anti-overfitting**: (1) pre-register before sweep; (2) each sweep cell = a trial (HLZ scaling implicit in PSR data-mining adj); (3) never re-run identical param grid hoping for different verdict; (4) holdout = most recent N%, never tuned on.

**Stop and ask**: unplumbed data needed; ROBUST graduation candidate (journal+stop, operator promotes); methodology change suggested; sweep touches protected strategy.

**Frequency-cap rationale**: not a research rate limit — guardrail on deploys to trading host. Many backtests/hour fine. Cap: regenerating spec-driven Java + JVM restart >every 4h, since each restart momentarily blanks the live process if both JVMs co-deploy.

## Frontend `/research` Dashboard
Admin-only, 7 panels: service health, per-JVM telemetry (heap/GC/threads/uptime/CPU%), scheduler, sweep activity, promotion candidates (promote/demote/reject), recent promotions, log tail. Telemetry polls 5s; rest 30s. URLs: `/research/sweeps`, `/research/log`.

## Quant-Grade Roadmap
Architecture is quant-grade in code structure but **single-asset-live in data plane**.
- **Q1 Data breadth**: Phase 3 ETH live plumbing (backtest done 2026-05-01; live multi-symbol subscription pending — biggest expected-ROI move). Phase 4 funding-rate ingestion (V34/V35/V36/V37 shipped). Phase 5 multi-window/regime (V38 shipped).
- **Q2 Operational**: Phase 6 frontend leaderboard. Phase 7 Prometheus + Slack/Telegram alerts (V39 alert_event shipped). Phase 8 risk model.
- **Q3 Search scale**: Phase 9 evolutionary search. Phase 10 OI+basis.
- **Skip**: full Gradle module split; DB-role activation beyond V14; real-time tick/microstructure; ML/prediction.
