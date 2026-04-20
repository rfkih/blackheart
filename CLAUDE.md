# Blackheart — Algorithmic Trading Platform

## Overview
Java-based algo trading platform; live trading and backtesting on Binance.

## Tech Stack
- **Language/Framework**: Java 21, Spring Boot 3.2.3, Gradle
- **Data**: PostgreSQL (primary), Redis (cache/pub-sub), Kafka (event streaming)
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
**Live** (`LiveOrchestratorCoordinatorService`) — accounts with 2+ active strategies on same interval:
- Entry phase: evaluates strategies in ascending `priorityOrder`; first entry signal wins.
- Active trade phase: only the strategy whose `accountStrategyId` matches the open trade is executed.

**Backtest** (`BacktestCoordinatorService`) — supports comma-separated `strategyCode` (e.g. `"LSR_V2,VCB"`):
- Same fan-out / ownership logic as live.
- Per-strategy params resolved via `strategyAccountStrategyIds` map; falls back to global `accountStrategyId`.

## Key Modules
| Module | Location | Key Classes |
|---|---|---|
| Strategy execution | `service/strategy/` | `StrategyExecutor`, `StrategyExecutorFactory`, `DefaultStrategyContextEnrichmentService`, `StrategyHelper` |
| Strategy impls | `service/strategy/` | `LsrStrategyService`, `VcbStrategyService`, `ExecutionTestService` |
| Strategy params | `service/strategy/` | `LsrStrategyParamService`, `VcbStrategyParamService`, `AccountStrategyService` |
| Live trading | `service/live/` | `LiveOrchestratorCoordinatorService`, `LiveTradingCoordinatorService`, `LiveTradingDecisionExecutorService`, `LiveTradeListenerService`, `LivePositionSnapshotMapper` |
| Backtest | `service/backtest/` | `BacktestCoordinatorService`, `BacktestTradeExecutorService`, `BacktestMetricsService`, `BacktestPersistenceService`, `BacktestStateService`, `BacktestEquityPointRecorder` |
| Trade mgmt | `service/trade/` | `TradeOpenService`, `TradeCloseService`, `TradeStateSyncService` |
| Market data | `service/marketdata/`, `stream/` | `BinanceWebSocketClient`, `MarketDataService`, `HistoricalDataService` |
| Clients | `client/` | `BinanceClientService`, `TokocryptoClientService`, `DeepLearningClientService` |
| Portfolio | `service/portfolio/` | `PortfolioService` |
| User / Auth | `service/user/` | `UserService`, `JwtService` |

## Key Models
| Model | Purpose |
|---|---|
| `User` | Platform user (email/password login, JWT) |
| `Account` | Exchange API account; `userId` FK to `User` |
| `AccountStrategy` | Links account → strategy; interval, capital allocation, priority, allow-long/short flags |
| `LsrStrategyParam` | Per-account-strategy LSR overrides; JSONB `param_overrides`, `@Version` optimistic lock |
| `VcbStrategyParam` | Per-account-strategy VCB overrides; same structure as LSR |
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
| `TREND_PULLBACK_SINGLE_EXIT` | (existing) | Trend-following w/ pullback entries, fixed 1.5:1 R:R |
| `RAHT_V1` | `RahtV1` | RahtV1 strategy |
| `TSMOM_V1` | `TsMomV1` | Time-series momentum |
| `TEST` | `ExecutionTestService` | Execution testing only |

## Per-Strategy Parameter System (LSR + VCB)
Both LSR and VCB support per-`accountStrategyId` overrides in PostgreSQL:
- **Storage**: `lsr_strategy_param` / `vcb_strategy_param` — JSONB `param_overrides`, `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6), `@Version` optimistic locking.
- **Param objects**: `LsrParams` / `VcbParams` — value objects, `@Builder.Default` fields, `defaults()` factory, `merge(Map)` overlay.
- **Cache**: Redis, `GenericJackson2JsonRedisSerializer`; key prefix `lsr:params:` / `vcb:params:`; TTL 1h. Evicted **after transaction commit** via `TransactionSynchronizationManager.afterCommit()`.
- **Concurrent insert safety**: `putParams` / `patchParams` catch `DataIntegrityViolationException` and retry with UPDATE.
- **REST**: `/api/v1/lsr-params/{accountStrategyId}` and `/api/v1/vcb-params/{accountStrategyId}` — GET, PUT, PATCH, DELETE + GET `/defaults`.
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
| `AccountStrategyController` | `/api/v1/account-strategies` | Inquiry account strategies by user |
| `LsrStrategyParamController` | `/api/v1/lsr-params` | LSR param CRUD |
| `VcbStrategyParamController` | `/api/v1/vcb-params` | VCB param CRUD |
| `BacktestController` | `/api/v1/backtest` | Submit and query backtests |
| `TradeController` | `/api/v1/trades` | Trade management |
| `TradeQueryController` | `/api/v1/trades` | Trade queries |
| `TradePnlQueryController` | `/api/v1/pnl` | P&L queries |
| `PortofolioController` | `/api/v1/portfolio` | Portfolio balances |
| `MarketQueryController` | `/api/v1/market` | Market data queries |
| `SchedulerController` | `/api/v1/scheduler` | Scheduler management |
| `MonteCarloController` | `/api/v1/montecarlo` | Monte Carlo simulation |

## Security
- JWT Bearer auth; `JwtService` issues and validates tokens.
- Public: `/healthcheck`, `/ws`, `/ws/**`, `/api/v1/users/register`, `/api/v1/users/login`.
- All other endpoints require `Authorization: Bearer <token>`.
- `jwtService.extractUserId(token)` — resolves caller's UUID for ownership checks.

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

### Live / Backtest Parity Rules
- `DefaultStrategyContextEnrichmentService` skips live DB queries when `source == "backtest"` — do not break this guard.
- `previousFeatureStore` must be populated for strategies declaring `requirePreviousFeatureStore = true`; live loads from DB, backtest sets it manually.
- Live bias candle must use `findLatestCompletedBySymbolAndInterval` (completed), not `findLatestBySymbolAndInterval` (may return forming candle).
- `executeOpenShort` in `LiveTradingDecisionExecutorService` reads `decision.getNotionalSize()` — strategies must set `notionalSize`, not `positionSize`, for SHORT entries.
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