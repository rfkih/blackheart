# Architecture reference

> Detailed module/endpoint/model maps. Loaded on demand by agents — the
> top-level `CLAUDE.md` keeps only invariants. Read this when wiring a
> new feature, tracing a flow, or onboarding a fresh session into a part
> of the codebase you don't already know.

## Topology — two JVMs (V14+)

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
            │                                       │
            └──────────► PostgreSQL ◄───────────────┘
```

Trading JVM never participates in research. Research JVM never connects to Binance. Communication is purely DB state. A research crash/OOM/restart cannot disturb live trading. See `research/DEPLOYMENT.md`.

## Live Trading Flow

```
Binance WS → FeatureStore → LiveOrchestratorCoordinatorService (multi-strategy fan-out)
→ LiveTradingCoordinatorService → DefaultStrategyContextEnrichmentService
→ StrategyExecutor → Decision → LiveTradingDecisionExecutorService
→ BinanceClientService → DB → LiveTradeListenerService → LivePnlPublisherService → WS
```

## Backtest Flow

```
BacktestService → BacktestCoordinatorService → MarketData/FeatureStore (DB)
→ StrategyExecutor → BacktestTradeExecutorService (fills/fees/slippage)
→ BacktestMetricsService → BacktestPersistenceService → BacktestEquityPointRecorder
```

## Multi-Strategy Orchestration

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

## Backtest interval restrictions
Engine ticks on `5m` monitor candle (`BacktestCoordinatorService.MONITOR_INTERVAL`). Strategy intervals restricted to **5m / 15m / 1h / 4h** at submit (`BacktestRunRequest.@Pattern`). Live trading still supports full range — restriction is backtest-only.

## Data availability
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

## Frontend `/research` Dashboard

Admin-only, 7 panels: service health, per-JVM telemetry (heap/GC/threads/uptime/CPU%), scheduler, sweep activity, promotion candidates (promote/demote/reject), recent promotions, log tail. Telemetry polls 5s; rest 30s. URLs: `/research/sweeps`, `/research/log`.

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

## External Services

**Binance** primary, **Tokocrypto** secondary. **FastAPI** :8000 ML/DL. **Node.js** :3000.
