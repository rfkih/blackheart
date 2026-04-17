# Blackheart - Algorithmic Trading Platform

## Project Overview
Java-based algorithmic trading platform supporting live trading and backtesting on Binance.

## Tech Stack
- **Language**: Java 21
- **Framework**: Spring Boot 3.2.3
- **Build**: Gradle
- **Database**: PostgreSQL (primary), Redis (cache/pub-sub), Kafka (event streaming)
- **Exchange**: Binance REST API + WebSocket
- **Technical Analysis**: TA4j
- **Real-time**: WebSocket/STOMP for live P&L streaming
- **Other**: Lombok, Jackson, Docker, Spring Security (HTTP Basic)

## Architecture

Two parallel execution paths sharing a common strategy interface:

### Live Trading Flow
```
Binance WebSocket → FeatureStore (pre-computed TA indicators)
→ LiveTradingCoordinatorService → StrategyExecutor → Decision
→ LiveTradingDecisionExecutorService → BinanceClientService → DB
→ LivePnlPublisherService → WebSocket clients
```

### Backtest Flow
```
BacktestService → BacktestCoordinatorService → MarketData/FeatureStore (DB)
→ StrategyExecutor → BacktestTradeExecutorService (simulates fills/fees/slippage)
→ BacktestMetricsService → BacktestPersistenceService
```

## Key Modules

| Module | Location | Key Classes |
|---|---|---|
| Strategy | `service/strategy/` | `StrategyExecutor`, `StrategyExecutorFactory`, `TrendPullbackSingleExit`, `RahtV1`, `TsMomV1` |
| Live Trading | `service/live/` | `LiveTradingCoordinatorService`, `LiveTradingDecisionExecutorService`, `LiveTradeListenerService` |
| Backtest | `service/backtest/` | `BacktestCoordinatorService`, `BacktestTradeExecutorService`, `BacktestMetricsService` |
| Trade Mgmt | `service/trade/` | `TradeOpenService`, `TradeCloseService`, `TradeStateSyncService` |
| Market Data | `service/marketdata/`, `stream/` | `BinanceWebSocketClient`, `MarketDataService`, `HistoricalDataService` |
| Clients | `client/` | `BinanceClientService`, `TokocryptoClientService`, `DeepLearningClientService` |
| Portfolio | `service/portfolio/` | `PortfolioService` |

## Key Models
- `Account` — user credentials and risk settings
- `Trades` / `TradePosition` — trade records with multi-leg exits (TP1/TP2/RUNNER)
- `MarketData` — OHLCV candlestick data
- `FeatureStore` — pre-computed technical indicators (EMA, ADX, RSI, MACD, ATR, Donchian, etc.)
- `AccountStrategy` — links account to enabled strategies with capital allocation
- `BacktestRun` / `BacktestTrade` — backtest execution and results

## Design Patterns
- **Strategy Pattern** — pluggable `StrategyExecutor` implementations
- **Factory Pattern** — `StrategyExecutorFactory` as strategy registry
- **Coordinator Pattern** — orchestrates complex multi-service flows
- **Context Object** — `EnrichedStrategyContext` carries all strategy inputs
- **Feature Pre-computation** — `FeatureStore` caches indicators for fast backtest queries
- **Multi-leg Exits** — `TradePosition` supports TP1/TP2/RUNNER structure

## Active Strategies
- `TREND_PULLBACK_SINGLE_EXIT` — trend-following with pullback entries, fixed 1.5:1 R:R
- `RAHT_V1` — RahtV1 strategy
- `TSMOM_V1` — time-series momentum strategy
- `TEST` — execution testing

## External Services
- **Binance** — primary exchange (REST + WebSocket)
- **Tokocrypto** — secondary exchange
- **FastAPI** (port 8000) — ML/deep learning predictions
- **Node.js** (port 3000) — additional service

## Security
- HTTP Basic Auth (stateless, CSRF disabled)
- Public: `/healthcheck`, `/ws`, `/ws/**`
- All other endpoints require authentication

## Working Rules

### General
- Prefer minimal, targeted changes over large refactors.
- Do not change public API contracts unless explicitly requested.
- Do not rename entities, DTO fields, or database columns unless explicitly requested.
- If schema changes are required, always provide migration SQL.
- Preserve backward compatibility whenever possible.

### Trading Logic Safety
- Do not simplify execution, fill, fee, or P&L logic without explaining the consequences.
- Always preserve consistency between live trading and backtest behavior unless divergence is intentional and documented.
- Respect Binance constraints such as lot size, precision, min notional, and available balance.
- When changing stop loss, take profit, trailing stop, or runner logic, explain:
    - what changed
    - why it changed
    - impact on trade lifecycle
    - impact on backtest parity

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
- Backtest persistence should remain efficient and should not add heavy writes inside the execution loop unless required.
- Prefer daily equity persistence over excessive per-candle persistence unless explicitly requested.

### Logging / Observability
- Add clear logs for strategy decisions, order execution, fills, stop updates, and reconciliation issues.
- Do not add noisy logs in high-frequency paths unless necessary.

### Architecture Discipline
- Keep strategy logic inside strategy modules.
- Keep orchestration inside coordinator services.
- Keep exchange-specific behavior inside client/services, not mixed into strategy logic.
- Do not move business logic into controllers.

### If Uncertain
- Ask whether the user wants a minimal patch or a structural refactor.
- For risky changes, explain first before editing.

## Common Commands
- Build: ./gradlew build
- Test: ./gradlew test
- Run app: ./gradlew bootRun
- Run specific test: ./gradlew test --tests "com.example.YourTest"

## Domain Invariants
- `Trades` is the parent trade record.
- `TradePosition` represents child legs such as SINGLE, TP1, TP2, RUNNER.
- Parent/child trade consistency must be preserved after every open/close/update flow.
- `FeatureStore` is the preferred source of precomputed indicators for strategies and backtests.
- Live trading and backtest paths share strategy logic through the common strategy interface.

## Do Not
- Do not rewrite architecture unless explicitly requested.
- Do not replace TA4j-based logic without justification.
- Do not bypass risk checks, fee handling, or fill simulation.
- Do not introduce hidden behavior changes in strategy execution.
- Do not make broad package moves or rename classes unnecessarily.
