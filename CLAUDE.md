# Blackheart — Algorithmic Trading Platform

## Overview
Java-based algo trading platform; live trading and backtesting on Binance.

## Tech Stack
- **Language/Framework**: Java 21, Spring Boot 3.3.5, Gradle
- **Data**: PostgreSQL (primary), Redis (cache/pub-sub), Kafka (event streaming)
- **Migrations**: Flyway (`db/flyway/V<N>__*.sql`); legacy `db/migration/*.sql` retained as reference
- **Exchange**: Binance REST + WebSocket
- **TA**: TA4j
- **Real-time**: WebSocket/STOMP (live P&L streaming)
- **Auth**: JWT Bearer, Spring Security
- **Other**: Lombok, Jackson, Docker, Hibernate 6

## Architecture
Two parallel execution paths share a common strategy interface.

### Live Trading Flow
```
Binance WebSocket → FeatureStore (pre-computed TA indicators)
→ LiveOrchestratorCoordinatorService (multi-strategy fan-out / ownership routing)
→ LiveTradingCoordinatorService → DefaultStrategyContextEnrichmentService
→ StrategyExecutor → Decision
→ LiveTradingDecisionExecutorService → BinanceClientService → DB
→ LiveTradeListenerService (stop/TP monitoring) → LivePnlPublisherService → WebSocket clients
```

### Backtest Flow
```
BacktestService → BacktestCoordinatorService → MarketData/FeatureStore (DB)
→ StrategyExecutor → BacktestTradeExecutorService (simulates fills/fees/slippage)
→ BacktestMetricsService → BacktestPersistenceService → BacktestEquityPointRecorder
```

### Multi-Strategy Orchestration
**Live** (`LiveOrchestratorCoordinatorService`) — invoked per `(account, interval)` event:
- Sorted by `accountStrategy.priorityOrder` ascending.
- Entry phase: fan-out stops at the first opener within the interval-group.
- Active-trade phase: routes only to the strategy whose `accountStrategyId` owns the open trade.
- Cap is **per-interval-group**: at most one active trade per `(account, interval)` tuple at any time. There is no global cap across intervals — different timeframe groups operate independently.

**Backtest** (`BacktestCoordinatorService`) — matches live exactly:
- Comma-separated `strategyCode` (e.g. `"LSR_V2,VCB"`); per-strategy params via `strategyAccountStrategyIds`.
- **Priority order**: executors sorted by persistent `accountStrategy.priorityOrder` ascending (declaration-order tiebreak). Live parity.
- **Per-interval-group cap**: `intervalGroupBusy` skips an entry if any strategy on the same interval already has an active trade or pending entry. The legacy global `maxConcurrentStrategies` field is still on `BacktestRun` but is no longer consulted.
- **Per-strategy bias data**: `preloadBiasDataPerStrategy` returns `Map<String, BiasData>` keyed by strategy code; loads cached by `biasInterval` so two strategies wanting the same bias share one DB query. Each strategy's enrichment receives only its own bias series.
- **Per-strategy pending entries**: `BacktestState.pendingEntriesByStrategy` (LinkedHashMap, deterministic). Concurrent same-bar entries from different strategies all queue and fill independently on the next bar's open price.
- **Trade `interval` stamping**: each `BacktestTrade` + `BacktestTradePosition` carries the strategy's resolved interval (not the run's primary), so trade history correctly reflects mixed-interval runs.
- **Same-bar re-entry**: allowed (matches live). The strategy step always runs after the listener step; a TP/SL exit on the same bar doesn't gate the next entry signal.

### Backtest Interval Restrictions
Backtest engine ticks on a `5m` monitor candle (`BacktestCoordinatorService.MONITOR_INTERVAL`). Strategy intervals are restricted to **5m / 15m / 1h / 4h** at submit time (`BacktestRunRequest.@Pattern`); finer intervals would silently miss bar closes and coarser ones aren't part of the supported strategy set. Live trading still supports the full range (e.g. 1m via WebSocket) — this restriction is backtest-only.

## Key Modules
| Module | Location | Key Classes |
|---|---|---|
| Strategy execution | `service/strategy/` | `StrategyExecutor`, `StrategyExecutorFactory`, `DefaultStrategyContextEnrichmentService`, `StrategyHelper` |
| Strategy impls | `service/strategy/` | `LsrStrategyService`, `VcbStrategyService`, `ExecutionTestService` |
| Strategy params | `service/strategy/` | `LsrStrategyParamService`, `VcbStrategyParamService`, `VboStrategyParamService`, `AccountStrategyService` |
| Live trading | `service/live/` | `LiveOrchestratorCoordinatorService`, `LiveTradingCoordinatorService`, `LiveTradingDecisionExecutorService`, `LiveTradeListenerService`, `LivePositionSnapshotMapper` |
| Backtest | `service/backtest/` | `BacktestCoordinatorService`, `BacktestTradeExecutorService`, `BacktestMetricsService`, `BacktestPersistenceService`, `BacktestStateService`, `BacktestEquityPointRecorder` |
| Trade mgmt | `service/trade/` | `TradeOpenService`, `TradeCloseService`, `TradeStateSyncService` |
| Market data | `service/marketdata/`, `stream/` | `BinanceWebSocketClient`, `MarketDataService`, `HistoricalDataService` |
| Clients | `client/` | `BinanceClientService`, `TokocryptoClientService`, `DeepLearningClientService` |
| Portfolio | `service/portfolio/` | `PortfolioService` |
| User / Auth | `service/user/` | `UserService`, `JwtService`, `AccountQueryService` |
| Audit | `service/audit/` | `AuditService` — service-layer recorder, transaction-bound, never throws |

## Key Models
| Model | Purpose |
|---|---|
| `User` | Platform user (email/password login, JWT) |
| `Account` | Exchange API account; `userId` FK to `User`. Risk levers: `maxConcurrentLongs`, `maxConcurrentShorts`, `maxConcurrentTrades` (nullable total cap; null = no total cap), `volTargetingEnabled`, `bookVolTargetPct` |
| `AccountStrategy` | Links account → strategy; interval, capital allocation, priority, allow-long/short flags. Soft-delete via `is_deleted` + `deleted_at` (preserves FK targets for historical trades/P&L). Editable via PATCH: `intervalName` (blocked if open trades exist), `priorityOrder` (1-99, no guard) |
| `AuditEvent` | Append-only audit log for security-sensitive mutations. Fields: `actorUserId`, `action`, `entityType`, `entityId`, `beforeData` (JSONB), `afterData` (JSONB), `reason`, `createdAt`. Writes are transaction-bound — a rolled-back mutation rolls back its audit row too. Indexed by actor, by (entity_type, entity_id), and by created_at |
| `LsrStrategyParam` | Per-account-strategy LSR overrides; JSONB `param_overrides`, `@Version` optimistic lock |
| `VcbStrategyParam` | Per-account-strategy VCB overrides; same structure as LSR |
| `VboStrategyParam` | Per-account-strategy VBO overrides; same structure as LSR / VCB |
| `Trades` | Parent trade record (status: OPEN / PARTIALLY_CLOSED / CLOSED) |
| `TradePosition` | Child leg: SINGLE, TP1, TP2, RUNNER |
| `MarketData` | OHLCV candlestick data |
| `FeatureStore` | Pre-computed indicators (EMA, ADX, RSI, MACD, ATR, BB, KC, Donchian, relVol, ER, signedER, etc.) |
| `BacktestRun` | Backtest config + result metadata; `strategyAccountStrategyIds` JSONB for per-strategy param mapping |
| `BacktestTrade` / `BacktestTradePosition` | Simulated trade records |
| `BacktestEquityPoint` | Per-candle equity curve snapshots |

## Active Strategies
| Code | Class | Description |
|---|---|---|
| `LSR` / `LSR_V2` | `LsrStrategyService` | Long/Short reversal; fully parameterized via `LsrParams` |
| `VCB` | `VcbStrategyService` | Volatility Compression Breakout v2.1; fully parameterized via `VcbParams` |
| `VBO` | `VolatilityBreakoutStrategyService` | Volatility Breakout (BB-width compression → expansion); fully parameterized via `VboParams` |
| `TREND_PULLBACK_SINGLE_EXIT` | (existing) | Trend-following w/ pullback entries, fixed 1.5:1 R:R |
| `RAHT_V1` | `RahtV1` | RahtV1 strategy |
| `TSMOM_V1` | `TsMomV1` | Time-series momentum |
| `TEST` | `ExecutionTestService` | Execution testing only |

## Account Strategy Lifecycle
- **Create**: `POST /api/v1/account-strategies` — verifies user owns the target account and that `strategyCode` resolves via `StrategyDefinitionRepository`. New rows default to `enabled=false`, `currentStatus="STOPPED"`, `is_deleted=false`. Users explicitly toggle `enabled` on to go live. Emits `STRATEGY_CREATED` audit event.
- **Activate / Deactivate**: `POST /api/v1/account-strategies/:id/activate` and `/deactivate` — flips `enabled`. Activate also deactivates any sibling on the same `(account, strategyDefinition, symbol, interval)` tuple atomically, blocking if that sibling has open trades. Emits `STRATEGY_ACTIVATED` / `STRATEGY_DEACTIVATED`.
- **Update**: `PATCH /api/v1/account-strategies/:id` — partial update. `intervalName` is blocked if open trades exist (mid-position TF change is unsafe); `priorityOrder` (1-99) has no guard since it only affects future entry fan-out. Emits `STRATEGY_UPDATED` with before/after snapshot of editable fields.
- **Soft-delete**: `DELETE /api/v1/account-strategies/:id` — sets `is_deleted=true`, `enabled=false`, `deleted_at=now()`. Blocked in-service if `TradesRepository.countOpenByAccountStrategyId > 0` (throws `IllegalStateException` with count). Historical trades / P&L continue to resolve the strategy because the row is preserved. Emits `STRATEGY_DELETED`.
- **Re-arm kill switch**: `POST /api/v1/account-strategies/:id/rearm` — clears the DD kill-switch trip after manual review. Emits `KILL_SWITCH_REARMED`.
- **Liveness flag is `enabled`, not `currentStatus`**: `current_status` is a legacy column that **no service writes**. Every row holds its seed value (`"STOPPED"`). All active-strategy queries filter `enabled = true AND is_deleted = false`; never rely on `current_status`. The frontend status badge is derived from `enabled`.
- **Read paths that filter `is_deleted`**: every query in `AccountStrategyRepository` (live orchestration, scheduler, UI list). Trade / P&L joins must **NOT** filter `is_deleted` — historical attribution depends on resolving deleted strategies by id.

## Per-Strategy Parameter System (LSR + VCB + VBO)
LSR, VCB, and VBO support per-`accountStrategyId` overrides in PostgreSQL:
- **Storage**: `lsr_strategy_param` / `vcb_strategy_param` / `vbo_strategy_param` — JSONB `param_overrides`, `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6), `@Version` optimistic locking.
- **Param objects**: `LsrParams` / `VcbParams` / `VboParams` — value objects, `@Builder.Default` fields, `defaults()` factory, `merge(Map)` overlay. `VboParams` additionally exposes `applyOverrides(Map)` for in-place mutation, and its merge handles boolean gate flags (`requireKcSqueeze`, `requireDonchianBreak`, `requireTrendAlignment`).
- **Cache**: Redis, `GenericJackson2JsonRedisSerializer`; key prefix `lsr:params:` / `vcb:params:` / `vbo:params:`; TTL 1h. Evicted **after transaction commit** via `TransactionSynchronizationManager.afterCommit()`.
- **Concurrent insert safety**: `putParams` / `patchParams` catch `DataIntegrityViolationException` and retry with UPDATE.
- **REST**: `/api/v1/lsr-params/{accountStrategyId}`, `/api/v1/vcb-params/{accountStrategyId}`, `/api/v1/vbo-params/{accountStrategyId}` — GET, PUT, PATCH, DELETE + GET `/defaults`.
- **Backtest integration**: `BacktestRun.strategyAccountStrategyIds` (JSONB `Map<String, UUID>`) maps strategy code → accountStrategyId; falls back to global `accountStrategyId`.

## Context Enrichment (Live vs Backtest)
`DefaultStrategyContextEnrichmentService` enriches `BaseStrategyContext` → `EnrichedStrategyContext`:
- **Bias candle (live)**: last **completed** bias candle via `end_time < now()` — never the current forming candle.
- **previousFeatureStore (live)**: fetched via `findPreviousBySymbolIntervalAndStartTime` when `requirements.isRequirePreviousFeatureStore()` is true. **Critical for compression-breakout strategies** (VCB gate 2 checks previous bar compression).
- **Backtest**: bias candle and previousFeatureStore are set manually by `BacktestCoordinatorService` after enrichment; enrichment service skips live-only DB queries when `executionMetadata.source == "backtest"`.

## Design Patterns
- **Strategy** — pluggable `StrategyExecutor` impls
- **Factory** — `StrategyExecutorFactory` as strategy registry
- **Coordinator** — orchestrates multi-service flows
- **Context Object** — `EnrichedStrategyContext` carries all strategy inputs
- **Feature Pre-computation** — `FeatureStore` caches indicators for fast backtest queries
- **Multi-leg Exits** — `TradePosition` supports TP1/TP2/RUNNER
- **Param Override** — JSONB overrides merged over hardcoded defaults; null-safe cache-aside with post-commit eviction

## API Endpoints
| Controller | Base Path | Purpose |
|---|---|---|
| `UserController` | `/api/v1/users` | Register, login, profile (GET/PATCH `/me`) |
| `AccountStrategyController` | `/api/v1/account-strategies` | List/get strategies by user (excludes soft-deleted); `POST` create; `PATCH /:id` accepts optional `intervalName` (blocked w/ open trades) and `priorityOrder` (1-99); `POST /:id/activate` `/deactivate` toggle liveness; `POST /:id/rearm` clear kill-switch; `DELETE /:id` soft-delete (blocked if OPEN/PARTIALLY_CLOSED trades exist). All mutations emit audit events |
| `AccountController` | `/api/v1/accounts` | `PATCH /:id/risk-config` updates `maxConcurrentLongs`, `maxConcurrentShorts`, `maxConcurrentTrades` (total cap; pass < 1 to clear), `volTargetingEnabled`, `bookVolTargetPct`. Null fields left unchanged. `PATCH /:id/credentials` rotates Binance API key/secret (re-encrypted via `EncryptedStringConverter`) |
| `LsrStrategyParamController` | `/api/v1/lsr-params` | LSR param CRUD |
| `VcbStrategyParamController` | `/api/v1/vcb-params` | VCB param CRUD |
| `VboStrategyParamController` | `/api/v1/vbo-params` | VBO param CRUD |
| `BacktestController` | `/api/v1/backtest` | Submit and query backtests |
| `TradeController` | `/api/v1/trades` | Trade management |
| `TradeQueryController` | `/api/v1/trades` | Trade queries |
| `TradePnlQueryController` | `/api/v1/pnl` | P&L queries |
| `PortfolioController` | `/api/v1/portfolio` | Portfolio balances. `GET ?accountId=<uuid>` scopes to a single owned account (verifies ownership, throws `AccessDeniedException` otherwise). Omit `accountId` to aggregate across every account the user owns: free/locked summed per asset, USDT recomputed once on the merged total so spot price is not double-applied. |
| `PortofolioController` | `/api/v1/portofolio` (also `/v1/portofolio` alias) | **Admin-only** legacy reload endpoint (`GET /reload`). Note the spelling — typo preserved for backward compat. Not the same as `PortfolioController`. |
| `MarketQueryController` | `/api/v1/market` | Market data queries |
| `SchedulerController` | `/api/v1/scheduler` | Scheduler management. `IP_MONITOR` job calls `IpMonitorService.checkAndNotifyIfChanged()` on its tick. |
| `MonteCarloController` | `/api/v1/montecarlo` | Monte Carlo simulation |
| `ResearchController` | `/api/v1/research` | **Mixed access** (no class-level `@PreAuthorize`). Sweeps endpoints (`POST/GET/DELETE /sweeps`, `POST /sweeps/:id/cancel`) and read-only `GET /tpr/params` are user-accessible — every sweep is created with the caller's `userId` and the get/cancel/delete handlers reject when `state.getUserId()` differs from the JWT subject. Admin-only via method-level `@PreAuthorize("hasRole('ADMIN')")`: `GET /backtest/:id/analysis` (no per-run ownership check), `PUT /tpr/params`, `POST /tpr/params/reset`, `GET /log`. Add new sweep-adjacent endpoints **without** `@PreAuthorize` and rely on the service-layer ownership check; add new global/mutating endpoints **with** `@PreAuthorize("hasRole('ADMIN')")`. |
| `ServerInfoController` | `/api/v1/server` | Server diagnostics. `GET /ip` calls ipify live (used by the broker-setup card). `GET /ip/status` returns the latest persisted `ServerIpLog` row — `{ currentIp, previousIp, event, recordedAt }` — written by the `IP_MONITOR` scheduler. The frontend `IpWhitelistBanner` polls this and warns the user when `event == "CHANGED"`. Don't make `/ip/status` call ipify; the whole point is to keep it cheap to poll. |

## Security
- JWT Bearer auth; `JwtService` issues and validates tokens.
- Public: `/healthcheck`, `/ws`, `/ws/**`, `/api/v1/users/register`, `/api/v1/users/login`.
- All other endpoints require `Authorization: Bearer <token>`.
- `jwtService.extractUserId(token)` — resolves caller's UUID for ownership checks.
- **JWT secret discipline**: `app.jwt.secret=${JWT_SECRET:<dev-sentinel>}`. The dev sentinel is committed for local-dev ergonomics; `JwtService.validateSecretOnStartup` refuses to boot if the sentinel is in use AND the active profile is not `dev`/`test`/`local`. Generate a prod secret with `id.co.blackheart.util.JwtSecretGenerator`.
- **Audit log**: every security-sensitive mutation (strategy CRUD, kill-switch rearm, account risk-config update) writes an `audit_event` row in the same transaction as the mutation. Service-layer wiring via `AuditService.record(...)` — never via AOP, so the actor and entity-id are always meaningful. Failures to record audit events are logged at WARN but never propagate (forensics-friendly, never breaks user flow).

## External Services
- **Binance** — primary exchange (REST + WebSocket)
- **Tokocrypto** — secondary exchange
- **FastAPI** (port 8000) — ML/DL predictions
- **Node.js** (port 3000) — additional service

## Working Rules

### General
- Prefer minimal, targeted changes over large refactors.
- Do not change public API contracts unless explicitly requested.
- Do not rename entities, DTO fields, or DB columns unless explicitly requested.
- If schema changes are required, always provide migration SQL.
- Preserve backward compatibility whenever possible.

### Trading Logic Safety
- Do not simplify execution, fill, fee, or P&L logic without explaining consequences.
- Always preserve consistency between live and backtest unless divergence is intentional and documented.
- Respect Binance constraints: lot size, precision, min notional, available balance.
- When changing stop loss, TP, trailing stop, or runner logic, explain: what changed, why, impact on trade lifecycle, impact on backtest parity.

### Live / Backtest Parity Rules (audited 2026-04-26)
- `DefaultStrategyContextEnrichmentService` skips live DB queries when `source == "backtest"` — do not break this guard.
- `previousFeatureStore` must be populated for strategies declaring `requirePreviousFeatureStore = true`; live loads from DB, backtest sets it manually.
- Live bias candle must use `findLatestCompletedBySymbolAndInterval` (completed), not `findLatestBySymbolAndInterval` (may return forming candle).
- **Cap is per-interval-group**, not global. `intervalGroupBusy` in the backtest mirrors live's "first opener wins per `(account, interval)` event". Do not reintroduce a global active-trade count for entry gating.
- **Bias data is per-strategy**. `preloadBiasDataPerStrategy` returns one `BiasData` per strategy code based on that strategy's own `requirements.biasInterval`. Do not collapse this back into a single merged bias series.
- **Priority order matches live**. `resolveStrategyExecutors` sorts by `accountStrategy.priorityOrder` ascending (declaration-order tiebreak). Don't reintroduce request-declaration-order as the primary sort.
- **Same-bar re-entry is allowed**. `handleStrategyStep` runs unconditionally after `handleListenerStep`; do not gate on `anyPositionClosed` or any aggregate state. Live has no such gate.
- **Per-strategy pending entries**. `BacktestState.pendingEntriesByStrategy` is keyed by uppercase strategy code (LinkedHashMap, deterministic iteration). Concurrent same-bar entries from different strategies all queue and fill independently.
- **Trade `interval` is the strategy's resolved interval**, not `backtestRun.getInterval()`. The pending entry carries it; `openTrade` stamps it on both the trade and its positions.

### Point-in-Time Discipline (audited 2026-04-26)
- Strategy execution gates on `BinanceWebSocketClient.isProcessable` which requires the Binance `k.x` closed-candle flag — entry decisions never see a forming bar.
- Bias-timeframe enrichment uses `findLatestCompletedBySymbolAndInterval(boundary=now)` with `start_time < now` — completed-only.
- `FeatureStoreRepository.findLatestBySymbolAndInterval` (no completed filter) is **decision-unsafe** and only used by `SentimentPublisherService` (informational broadcast). The Javadoc on that method now warns explicitly against decision-path use; honor it. Add a completed-filter variant if a new caller emerges.
- When wiring a new strategy or feature read on the live path, the rule is: anything that influences an entry/exit price level OR a sizing decision must read completed-only data. Anything informational (UI, alerts) can use the latest-including-forming variant.
- Live entry sizing fields in `LiveTradingDecisionExecutorService`: `executeOpenLong` reads `decision.getNotionalSize()` (USDT, checked against the USDT portfolio balance); `executeOpenShort` reads `decision.getPositionSize()` (BTC qty, checked against the BTC portfolio balance). Strategies must set the correct field in the correct currency or the executor silently falls back to its own sizing. For SHORT, use `StrategyHelper.calculateShortPositionSize` (BTC), **not** `calculateEntryNotional(SIDE_SHORT)` which returns USDT and will always fail the BTC balance guard.
- `buildPositionSnapshot` in `LiveTradingCoordinatorService` returns `hasOpenPosition=false` when no OPEN `TradePosition` rows exist, regardless of parent `Trades` status.

### Code Change Format
When implementing changes, respond with:
1. Root cause or objective
2. Proposed solution
3. Files/classes affected
4. Risks and edge cases
5. Code changes
6. Test checklist

### Database / Persistence
- Avoid unnecessary writes in hot paths.
- Use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB columns in Hibernate 6 — NOT `AttributeConverter<Map, String>`.
- Backtest persistence must stay efficient; do not add heavy writes inside the execution loop unless required.
- Prefer daily equity persistence over excessive per-candle persistence unless explicitly requested.

### Migration Strategy
- **Flyway** is the source of truth: new migrations go in `src/main/resources/db/flyway/V<N>__name.sql`. Filename convention: `V<n>__<verb>_<noun>.sql` (e.g. `V4__add_audit_reason_index.sql`).
- **Baseline**: pre-Flyway state is stamped as V1 via `spring.flyway.baseline-on-migrate=true`. Existing prod schemas built up via the legacy manual files are accepted as-is on first Flyway run.
- **Legacy `db/migration/`** files are kept as reference documentation; Flyway does not read them. The `apply_session_migrations.sql` consolidated script is no longer the canonical apply path going forward.
- **Idempotency**: every migration uses `IF NOT EXISTS` / `IF EXISTS` so re-running is safe. Flyway records applied versions in `flyway_schema_history`.
- **`ddl-auto=validate`** stays on as a safety net — Flyway applies the change, Hibernate validates the column exists on startup.

### Logging / Observability
- Clear logs for strategy decisions, order execution, fills, stop updates, reconciliation issues.
- No noisy logs in high-frequency paths unless necessary.

### Architecture Discipline
- Keep strategy logic inside strategy modules.
- Keep orchestration inside coordinator services.
- Keep exchange-specific behavior inside client/services — not mixed into strategy logic.
- Do not move business logic into controllers.
- Strategy param services (`LsrStrategyParamService`, `VcbStrategyParamService`) follow a fixed pattern — apply the same pattern when adding new parameterized strategies.

### If Uncertain
- Ask whether user wants a minimal patch or a structural refactor.
- For risky changes, explain first before editing.

## Common Commands
- Build: `./gradlew build`
- Test: `./gradlew test`
- Run app: `./gradlew bootRun`
- Run specific test: `./gradlew test --tests "com.example.YourTest"`

## Domain Invariants
- `Trades` is the parent trade record.
- `TradePosition` represents child legs: SINGLE, TP1, TP2, RUNNER.
- Parent/child trade consistency must be preserved after every open/close/update flow.
- `FeatureStore` is the preferred source of precomputed indicators for strategies and backtests.
- Live and backtest paths share strategy logic through the common strategy interface.
- A `PARTIALLY_CLOSED` parent `Trades` with no OPEN `TradePosition` children is not a tradable position — do not route strategy into position management for it.
- Per-strategy params always fall back to `defaults()` when no override row exists or `accountStrategyId` is null.

## Do Not
- Do not rewrite architecture unless explicitly requested.
- Do not replace TA4j-based logic without justification.
- Do not bypass risk checks, fee handling, or fill simulation.
- Do not introduce hidden behavior changes in strategy execution.
- Do not make broad package moves or rename classes unnecessarily.
- Do not use `@Convert(converter = JsonMapConverter.class)` for JSONB — use `@JdbcTypeCode(SqlTypes.JSON)`.
- Do not evict Redis cache inside a `@Transactional` method — use `afterCommit()` via `TransactionSynchronizationManager`.