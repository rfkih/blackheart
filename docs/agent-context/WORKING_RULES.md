# Working rules — full text

> Detailed working-rules + invariants reference. Loaded on demand. The
> top-level CLAUDE.md keeps the headline list; the depth lives here.

## General
Minimal, targeted changes over large refactors. Don't change public API contracts, rename entities/DTO fields/DB columns unless requested. Schema changes always include migration SQL. Preserve back-compat whenever possible.

## Trading Logic Safety
Don't simplify execution/fill/fee/P&L without explaining consequences. Preserve live↔backtest consistency unless divergence is intentional+documented. Respect Binance constraints (lot size, precision, min notional, balance). When changing SL/TP/trailing/runner: explain what changed, why, lifecycle impact, parity impact.

## Live / Backtest Parity (audited 2026-04-26)
- `DefaultStrategyContextEnrichmentService` skips live DB queries when `source=="backtest"` — don't break this guard.
- `previousFeatureStore` must populate when `requirePreviousFeatureStore=true`; live from DB, backtest manual.
- Live bias must use `findLatestCompletedBySymbolAndInterval` (completed), not `findLatestBySymbolAndInterval` (may return forming).
- **Cap is per-interval-group**, not global. `intervalGroupBusy` mirrors live's "first opener wins per `(account,interval)`". Don't reintroduce a global active-trade count for entry gating.
- **Bias data is per-strategy**. `preloadBiasDataPerStrategy` returns one `BiasData` per strategy code from its own `requirements.biasInterval`. Don't collapse back to single merged series.
- **Priority order matches live**: `resolveStrategyExecutors` sorts by `accountStrategy.priorityOrder` asc (declaration-order tiebreak). Don't reintroduce request-declaration-order as primary sort.
- **Same-bar re-entry allowed**. `handleStrategyStep` runs unconditionally after `handleListenerStep`; don't gate on `anyPositionClosed` or aggregate state. Live has no such gate.
- **Per-strategy pending entries**: `BacktestState.pendingEntriesByStrategy` keyed by uppercase strategy code (LinkedHashMap, deterministic).
- **Trade `interval` is strategy's resolved interval**, not `backtestRun.getInterval()`. Pending entry carries it; `openTrade` stamps trade+positions.

## Point-in-Time Discipline (audited 2026-04-26)
- Strategy execution gates on `BinanceWebSocketClient.isProcessable` (Binance `k.x` closed-candle flag) — entries never see forming bar.
- Bias-TF enrichment: `findLatestCompletedBySymbolAndInterval(boundary=now)` w/ `start_time < now` — completed-only.
- `FeatureStoreRepository.findLatestBySymbolAndInterval` (no completed filter) is **decision-unsafe** — only used by `SentimentPublisherService` (informational). Javadoc warns; honor it. Add a completed-filter variant if a new caller emerges.
- Rule: anything influencing entry/exit price level OR sizing must read completed-only. Anything informational (UI/alerts) can use latest-including-forming.
- Live entry sizing in `LiveTradingDecisionExecutorService`: `executeOpenLong` reads `decision.getNotionalSize()` (USDT, checked vs USDT balance); `executeOpenShort` reads `decision.getPositionSize()` (BTC qty, checked vs BTC balance). Wrong field/currency → executor silently falls back to its own sizing. For SHORT use `StrategyHelper.calculateShortPositionSize` (BTC), **not** `calculateEntryNotional(SIDE_SHORT)` (returns USDT, always fails BTC guard).
- `buildPositionSnapshot` in `LiveTradingCoordinatorService` returns `hasOpenPosition=false` when no OPEN `TradePosition` rows exist, regardless of parent `Trades` status.

## Code Change Format
1. Root cause/objective. 2. Proposed solution. 3. Files/classes affected. 4. Risks/edge cases. 5. Code changes. 6. Test checklist.

## Database / Persistence
- Avoid unnecessary writes in hot paths.
- Use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB in Hibernate 6 — NOT `AttributeConverter<Map,String>`.
- Backtest persistence stays efficient; no heavy writes inside execution loop unless required.
- Prefer daily equity persistence over per-candle unless requested.

## Logging / Observability
Clear logs for strategy decisions, order execution, fills, stop updates, reconciliation. No noisy logs in high-frequency paths.

## Architecture Discipline
Strategy logic in strategy modules; orchestration in coordinators; exchange-specific in client/services. Don't move business logic into controllers. Strategy param services follow a fixed pattern — reuse it for new parameterized strategies.

## If Uncertain
Ask: minimal patch or structural refactor? For risky changes, explain first.

## Domain Invariants
- `Trades` is parent; `TradePosition` is child leg (SINGLE/TP1/TP2/RUNNER).
- Parent/child consistency preserved after every open/close/update.
- `FeatureStore` is preferred precomputed-indicator source.
- Live and backtest share strategy logic via common interface.
- A `PARTIALLY_CLOSED` parent w/ no OPEN children is not tradable — don't route into position management.
- Per-strategy params fall back to `defaults()` when no override row or `accountStrategyId` null.

## Security
- JWT Bearer; `JwtService` issues+validates.
- Public: `/healthcheck`, `/ws`, `/ws/**`, `/api/v1/users/register`, `/api/v1/users/login`. All else: `Authorization: Bearer <token>`.
- `jwtService.extractUserId(token)` resolves caller UUID for ownership checks.
- **JWT secret**: `app.jwt.secret=${JWT_SECRET:<dev-sentinel>}`. `JwtService.validateSecretOnStartup` refuses boot if sentinel used + active profile is not `dev`/`test`/`local`. Generate prod secret via `id.co.blackheart.util.JwtSecretGenerator`.
- **Audit log**: every security-sensitive mutation writes `audit_event` in same transaction as mutation. Service-layer `AuditService.record(...)` — never AOP. Failures logged WARN, never propagate.

## Active Strategies (full table)

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

## Quant-Grade Roadmap

Architecture is quant-grade in code structure but **single-asset-live in data plane**.
- **Q1 Data breadth**: Phase 3 ETH live plumbing (backtest done 2026-05-01; live multi-symbol subscription pending — biggest expected-ROI move). Phase 4 funding-rate ingestion (V34/V35/V36/V37 shipped). Phase 5 multi-window/regime (V38 shipped).
- **Q2 Operational**: Phase 6 frontend leaderboard. Phase 7 Prometheus + Slack/Telegram alerts (V39 alert_event shipped). Phase 8 risk model.
- **Q3 Search scale**: Phase 9 evolutionary search. Phase 10 OI+basis.
- **Skip**: full Gradle module split; DB-role activation beyond V14; real-time tick/microstructure; ML/prediction.
