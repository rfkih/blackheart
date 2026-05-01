# Blackheart — Algorithmic Trading Platform

## Overview
Java-based algo trading platform; live trading and backtesting on Binance.

> **Future-state blueprint**: see `docs/PARAMETRIC_ENGINE_BLUEPRINT.md` for the parametric trading engine — the spec-driven architecture all new strategies will flow through. Hand-written Java strategies (LSR, VCB, VBO) remain as the proven legacy baseline and are explicitly preserved.

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
Two parallel execution paths share a common strategy interface, **served by two separate JVMs (V14+)**.

### Deployment topology (production-grade)
```
Trading JVM (port 8080)              Research JVM (port 8081)
─────────────────────                ────────────────────────
BlackheartApplication                BlackheartResearchApplication
profile = dev (or prod)              profile = dev,research
Heap: -Xms2g -Xmx2g                  Heap: -Xms512m -Xmx1500m
Built from: tradingBootJar           Built from: researchBootJar
Includes: live trading, market WS,   Includes: backtest, research,
          schedulers, P&L publish              monte carlo, dev backdoor
Excludes: research/, backtest/,      Excludes: BinanceWebSocketClient,
          montecarlo/, dev-tooling             schedulers, P&L publish,
          (controllers @Profile                Telegram bot
           ("research") AND                   (all gated @Profile("!research"))
           tradingBootJar exclusions)
            │                                       │
            └──────────► PostgreSQL ◄───────────────┘
                         (shared)
```
The trading JVM never participates in research. The research JVM never connects to Binance. Communication is purely through DB state. A research crash, OOM, or restart cannot disturb live trading. See `research/DEPLOYMENT.md` for the full operational story.

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

### Data Availability (current state)
**Only `BTCUSDT` data is plumbed end-to-end.** The Binance WebSocket client (`BinanceWebSocketClient.SYMBOL`) is hardcoded to `BTCUSDT`, and `MarketData` / `FeatureStore` backfill has only been performed for that symbol. Backtests on `ETHUSDT`, `SOLUSDT`, etc. will return empty result sets because the underlying market-data tables are empty for those symbols.

**Implication for strategy research:** any new strategy proposal that uses a non-BTC symbol (e.g. ETH/BTC pairs trade, alt-coin breakout) requires backend data plumbing first — at minimum: WebSocket subscription change, historical `MarketData` backfill, and `FeatureStore` indicator computation backfill. That's typically a half-day of work per new symbol; budget it before starting strategy research on a non-BTC instrument.

**To extend to a new symbol:** parameterise `BinanceWebSocketClient.SYMBOL`, run the historical backfill jobs (see `MarketDataService` / `HistoricalDataService`), and verify FeatureStore columns are populated for the new symbol+interval combinations the strategy needs.

## Key Modules
| Module | Location | Key Classes |
|---|---|---|
| Strategy execution | `service/strategy/` | `StrategyExecutor`, `StrategyExecutorFactory`, `DefaultStrategyContextEnrichmentService`, `StrategyHelper` |
| Strategy impls | `service/strategy/` | `LsrStrategyService`, `VcbStrategyService`, `ExecutionTestService` |
| Strategy params | `service/strategy/` | `LsrStrategyParamService`, `VcbStrategyParamService`, `VboStrategyParamService` (legacy, per-strategy); `StrategyParamService` (unified, **spec-driven only**, M1+); `AccountStrategyService` |
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
| `StrategyParam` | Unified per-account-strategy overrides for **spec-driven strategies only** (M1+). Raw JSONB `param_overrides`. Legacy strategies (LSR/VCB/VBO) keep their own per-strategy tables; this one serves only engine-driven specs. |
| `StrategyDefinition` | Strategy registry — extended with `archetype` (`LEGACY_JAVA` for hand-coded; archetype name for spec-driven), `archetypeVersion`, `specJsonb` (full spec for spec-driven), `isDeleted`/`deletedAt` (soft-delete). |
| `StrategyDefinitionHistory` | Append-only spec mutation audit log. One row per INSERT/UPDATE/DELETE/UPGRADE on `StrategyDefinition`. |
| `SpecTrace` | Per-evaluation engine decision trace (backtest dense, live 1% sample). Drives `SpecTraceViewer` UI and engine error-rate kill-switch. |
| `Trades` | Parent trade record (status: OPEN / PARTIALLY_CLOSED / CLOSED) |
| `TradePosition` | Child leg: SINGLE, TP1, TP2, RUNNER |
| `MarketData` | OHLCV candlestick data |
| `FeatureStore` | Pre-computed indicators (EMA, ADX, RSI, MACD, ATR, BB, KC, Donchian, relVol, ER, signedER, etc.) |
| `BacktestRun` | Backtest config + result metadata; `strategyAccountStrategyIds` JSONB for per-strategy param mapping |
| `BacktestTrade` / `BacktestTradePosition` | Simulated trade records |
| `BacktestEquityPoint` | Per-candle equity curve snapshots |

## Active Strategies
| Code | Class | Status | Description |
|---|---|---|---|
| `LSR` | `LsrStrategyService` | **Production — profitable** | Long/Short reversal; fully parameterized via `LsrParams` |
| `VCB` | `VcbStrategyService` | **Production — profitable** | Volatility Compression Breakout v2.1; fully parameterized via `VcbParams` |
| `VBO` | `VolatilityBreakoutStrategyService` | **Production — profitable** | Volatility Breakout (BB-width compression → expansion); fully parameterized via `VboParams` |
| `TPR` | `TrendPullbackStrategyService` | Research / not yet profitable | Trend-following w/ pullback entries (reclaim signal); position-management exits (break-even, trail, hold) |
| `TEST` | `ExecutionTestService` | Execution-only | Execution testing harness — not for live trading |

> Strategy codes are the literal `STRATEGY_CODE` constant on each `StrategyExecutor` impl. The legacy `LSR_V2` / `TREND_PULLBACK_SINGLE_EXIT` / `RAHT_V1` / `TSMOM_V1` / `DCT` codes are no longer registered — do not seed `account_strategy.strategy_code` with them.
>
> Production status reflects live-trading P&L results, not code completeness. When recommending strategy changes or adding new strategies, treat LSR/VCB/VBO as the proven baseline whose edges should not be undermined; TPR is a candidate for tuning rather than a benchmark.
>
> **Discarded strategies:**
> - `DCT` (Donchian Channel Trend, 4h) was researched and graduated to production scaffolding 2026-04-27, then discarded the same day — backtest peaked at ~10%/yr (right at the bar but no margin). Code, param table, controller, and migration removed (V8 drops `dct_strategy_param`). Lesson: a strategy that *just* clears the 10%/yr bar isn't worth a production slot when the existing book is already at +20%+ each. Future trend-followers should target a different timeframe or instrument, not 4h BTCUSDT.
> - `BBR` (Bollinger Reversal, 1h) and `CMR` (Chop Mean Reversion, 15m) were removed 2026-04-30. BBR was the original Phase 2 spec-language worked example (`research/specs/bbr.yml`) and was discarded as NO_EDGE; CMR was a hand-written research strategy that never traded (n=0). Both Java executor classes deleted, factory wiring removed, parity test (`MeanReversionOscillatorParityTest`) deleted with BBR. The `mean_reversion_oscillator` archetype template stays — it's still a valid spec target, just no live strategy uses it.

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
| `ResearchQueueController` | `/api/v1/research/queue` | **Mixed access**. Admin CRUD: `GET`, `POST`, `PATCH /:id`, `DELETE /:id` (all `@PreAuthorize("hasRole('ADMIN')")`). User-scoped: `POST /me` enqueues work with `created_by = jwtUserId` and no admin role check — mirrors `/sweeps` ownership so the frontend can submit research work without an admin session. Orchestrator tick path picks up admin- and user-queued rows identically. |
| `ResearchController` | `/api/v1/research` | **Mixed access** (no class-level `@PreAuthorize`). Sweeps endpoints (`POST/GET/DELETE /sweeps`, `POST /sweeps/:id/cancel`) and read-only `GET /tpr/params` are user-accessible — every sweep is created with the caller's `userId` and the get/cancel/delete handlers reject when `state.getUserId()` differs from the JWT subject. Admin-only via method-level `@PreAuthorize("hasRole('ADMIN')")`: `GET /backtest/:id/analysis` (no per-run ownership check), `PUT /tpr/params`, `POST /tpr/params/reset`, `GET /log`. Add new sweep-adjacent endpoints **without** `@PreAuthorize` and rely on the service-layer ownership check; add new global/mutating endpoints **with** `@PreAuthorize("hasRole('ADMIN')")`. |
| `ServerInfoController` | `/api/v1/server` | Server diagnostics. `GET /ip` calls ipify live (used by the broker-setup card). `GET /ip/status` returns the latest persisted `ServerIpLog` row — `{ currentIp, previousIp, event, recordedAt }` — written by the `IP_MONITOR` scheduler. The frontend `IpWhitelistBanner` polls this and warns the user when `event == "CHANGED"`. Don't make `/ip/status` call ipify; the whole point is to keep it cheap to poll. |

## Security
- JWT Bearer auth; `JwtService` issues and validates tokens.
- Public: `/healthcheck`, `/ws`, `/ws/**`, `/api/v1/users/register`, `/api/v1/users/login`.
- All other endpoints require `Authorization: Bearer <token>`.
- `jwtService.extractUserId(token)` — resolves caller's UUID for ownership checks.
- **JWT secret discipline**: `app.jwt.secret=${JWT_SECRET:<dev-sentinel>}`. The dev sentinel is committed for local-dev ergonomics; `JwtService.validateSecretOnStartup` refuses to boot if the sentinel is in use AND the active profile is not `dev`/`test`/`local`. Generate a prod secret with `id.co.blackheart.util.JwtSecretGenerator`.
- **Audit log**: every security-sensitive mutation (strategy CRUD, kill-switch rearm, account risk-config update) writes an `audit_event` row in the same transaction as the mutation. Service-layer wiring via `AuditService.record(...)` — never via AOP, so the actor and entity-id are always meaningful. Failures to record audit events are logged at WARN but never propagate (forensics-friendly, never breaks user flow).

## Strategy Promotion Pipeline (V15+)

Strategy lifecycle is now formal. Every transition is logged.
```
RESEARCH       → enabled=false                    (research-mode only)
PAPER_TRADE    → enabled=true,  simulated=true    (live signals, no real orders)
PROMOTED       → enabled=true,  simulated=false   (real capital)
DEMOTED        → enabled=false, simulated=false
REJECTED       → enabled=false, simulated=false
```

Allowed transitions (DB-enforced via `chk_promotion_states`):
- `RESEARCH → PAPER_TRADE`
- `PAPER_TRADE → PROMOTED` or `→ REJECTED`
- `PROMOTED → DEMOTED` or `→ PAPER_TRADE` (emergency yank)
- `DEMOTED → PAPER_TRADE`
- `REJECTED → PAPER_TRADE`

**Live executor guardrail** (`LiveTradingDecisionExecutorService.execute()`): when `accountStrategy.simulated=true`, **only OPEN_LONG/OPEN_SHORT decisions are diverted** to `paper_trade_run`. CLOSE_*/UPDATE_POSITION_MANAGEMENT always fall through to real execution — otherwise an emergency demote on a live position would strand it. Critical invariant; do not modify the scope of the `simulated` check without re-reading the bug-3 audit notes in `research/DEPLOYMENT.md`.

**Operator interface**: `POST /api/v1/strategy-promotion/{accountStrategyId}/promote` with `{toState, reason, evidence}`. Atomic — state flip + log row in same `@Transactional`. Pessimistic write lock prevents concurrent promotions racing. See `StrategyPromotionService`.

**New strategies default to `simulated=true`** (per `AccountStrategyService.create()`) — safe path is the default path. Direct UPDATE on `simulated` is technically possible but bypasses the audit trail; operators must use the controller.

## Strategy Spec Language (Phase 2)

New strategies can be expressed as YAML specs that codegen into Java. Editing YAML is safer than editing Java directly.

```
research/specs/<code>.yml          research/templates/
─────────────────────              ──────────────────
30-line YAML                       4 archetype templates:
                                     mean_reversion_oscillator
   ↓                                 trend_pullback
codegen-strategy.py                  donchian_breakout
   --validate                        momentum_mean_reversion
   --update-factory                 + _common_helpers.java.tmpl
   --check                          + _common_params_helpers.java.tmpl
   ↓
deploy-from-spec.sh
   → spec → codegen → factory wire → compile → restart → seed → queue
```

Workflow:
```bash
# Validate before generating
python3 research/scripts/codegen-strategy.py --spec research/specs/your-strategy.yml --validate

# One-command end-to-end
bash research/scripts/deploy-from-spec.sh \
    --spec research/specs/your-strategy.yml \
    --interval 1h \
    --sweep '{"params":[{"name":"PARAM","values":[v1,v2,v3,v4]}]}' \
    --iter-budget 4
```

Spec format documented in `research/specs/SCHEMA.md`. Validator type-checks default values, archetype-specific entry fields, version. Codegen produces ~290-line Java classes (down from ~440 pre-refactor) thanks to the `_common_helpers` extraction.

**Layer 2 autonomous generation** (when CronCreate is armed): the agent picks an archetype not yet in `account_strategy`, writes a YAML spec, runs `deploy-from-spec.sh`. 4-hour frequency cap per `/tmp/last-strategy-gen.txt`. Compile-failure rollback via `deploy-strategy.sh`'s factory-restore mechanism.

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
- **Current head: V28.** Latest migrations:
  - `V14__create_db_role_separation.sql` — creates `blackheart_trading` (full DML on `public`) and `blackheart_research` (SELECT on operational tables, full DML on backtest/research/promotion tables). Both NOLOGIN; operator activates by setting passwords. See `research/DB_USER_SEPARATION.md` for activation steps.
  - `V15__create_promotion_pipeline.sql` — adds `account_strategy.simulated` (default false), `paper_trade_run` table, `strategy_promotion_log` table with CHECK constraint on legal state transitions.
  - `V16__create_strategy_param.sql` — unified parameter override table for **spec-driven strategies only** (Parametric Engine M1). Legacy `lsr_strategy_param`/`vcb_strategy_param`/`vbo_strategy_param` tables intentionally untouched and continue serving LSR/VCB/VBO. See `docs/PARAMETRIC_ENGINE_BLUEPRINT.md` §16.
  - `V17__add_spec_columns_strategy_definition.sql` — adds `archetype` (default `LEGACY_JAVA`), `archetype_version`, `spec_jsonb`, `spec_schema_version`, `is_deleted`, `deleted_at`. Existing rows default to `LEGACY_JAVA` so the factory keeps routing them to hand-coded Java executors.
  - `V18__create_strategy_definition_history.sql` — append-only audit log of every spec mutation (INSERT/UPDATE/DELETE/UPGRADE).
  - `V19__create_spec_trace.sql` — engine decision audit trail (one row per evaluation; backtest dense, live 1% sample). Used by `SpecTraceViewer` UI and `EngineMetrics` kill-switch.
  - `V20__add_spec_change_notify_trigger.sql` / `V21__split_spec_change_notify_triggers.sql` — Postgres LISTEN/NOTIFY hooks for spec mutations (research JVM picks up archetype reloads without restart).
  - `V22__add_funding_rate_to_backtest_run.sql` — funding-rate columns on `backtest_run` for funding-aware archetypes.
  - `V23__create_research_control.sql` — operator-controlled kill-switch + global flags for the research orchestrator.
  - `V24__create_error_log.sql` / `V25__grant_research_error_log.sql` — system-wide error capture (DbErrorAppender), readable + flippable by `blackheart_research` for the developer agent.
  - `V26__create_code_review_finding.sql` — developer-agent triage records linked to error_log fingerprints.
  - `V27__add_scheduler_job_last_run.sql` — scheduler observability column.
  - `V28__create_idempotency_record.sql` — Postgres-backed idempotency cache for the **research-orchestrator FastAPI service**. Survives orchestrator restarts so retries across deploys don't double-submit a queue insert / walk-forward. Granted to `blackheart_research`. TTL ~24h, drained by `DELETE WHERE expires_at < NOW()`.
- **Baseline**: pre-Flyway state is stamped as V1 via `spring.flyway.baseline-on-migrate=true`. Existing prod schemas built up via the legacy manual files are accepted as-is on first Flyway run.
- **Legacy `db/migration/`** files are kept as reference documentation; Flyway does not read them. The `apply_session_migrations.sql` consolidated script is no longer the canonical apply path going forward.
- **Idempotency**: every migration uses `IF NOT EXISTS` / `IF EXISTS` so re-running is safe. Flyway records applied versions in `flyway_schema_history`.
- **`ddl-auto=validate`** stays on as a safety net — Flyway applies the change, Hibernate validates the column exists on startup.
- **Research JVM has Flyway disabled** (`application-research.properties`). Trading JVM owns schema migrations; research JVM uses whatever schema is already there.

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

### Build
```bash
./gradlew build                    # full build incl. tests
./gradlew compileJava              # fast Java-only compile (validates strategy code)
./gradlew tradingBootJar           # build/libs/blackheart-trading-*.jar (excludes research code)
./gradlew researchBootJar          # build/libs/blackheart-research-*.jar (includes dev-tooling)
./gradlew bootRun                  # dev-mode trading service on port 8080 (DEPRECATED — use JAR-based launch)
```

### Run (production-grade two-JVM)
```bash
# Trading JVM (port 8080, long-lived)
java -Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
    -jar build/libs/blackheart-trading-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=dev --server.port=8080

# Research JVM (port 8081, restart-safe)
java -Xms512m -Xmx1500m \
    -jar build/libs/blackheart-research-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=dev,research --server.port=8081

# Or via launchers:
bash research/scripts/run-trading-service.sh --background
bash research/scripts/run-research-service.sh --background

# Auto-restart watcher for research JVM (research crash → auto-recovery)
bash research/scripts/watch-research-jvm.sh &
```

### Research operations

**Primary path: research-orchestrator HTTP API** (see `research-orchestrator/` and the "Research Orchestrator (FastAPI, V28+)" section below). Agents and the frontend should use this; bash scripts remain only as operator fallback when the orchestrator is down or for ops tasks the API doesn't cover.

```bash
# ── Primary: HTTP API on the orchestrator (port 8082, loopback) ──────────
# Auth: X-Orch-Token + X-Agent-Name headers. Worked examples in
# research-orchestrator/README.md and `GET /agent/playbook`.

# Status / leaderboard
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     http://127.0.0.1:8082/agent/state
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     'http://127.0.0.1:8082/leaderboard?limit=15'

# Tick (one iteration end-to-end: claim → submit → poll → analyse → write)
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: tick-$(date +%s)" \
     http://127.0.0.1:8082/tick

# Walk-forward (after a SIGNIFICANT_EDGE tick parks the queue)
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: walk-$(date +%s)" \
     -d '{"queue_id":"...","n_folds":6}' \
     http://127.0.0.1:8082/walk-forward

# Queue work — agent boundary (X-Orch-Token):
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: q-$(date +%s)" \
     -d '{"strategyCode":"LSR","intervalName":"1h","sweepConfig":{...},"iterBudget":4}' \
     http://127.0.0.1:8082/queue
# Queue work — frontend / user boundary (JWT, no orch token):
#   POST /api/v1/research/queue/me on the trading JVM (proxied to research JVM)

# ── Phase 2 spec workflow (still bash-driven; codegen has no HTTP wrapper)
python3 research/scripts/codegen-strategy.py --spec research/specs/<code>.yml --validate
bash research/scripts/deploy-from-spec.sh --spec research/specs/<code>.yml --interval 1h --sweep '...' --iter-budget 4

# ── Fallback: bash drivers (operator-only; do not script agents against these)
bash research/scripts/research-tick.sh                     # single tick (manual)
bash research/scripts/run-continuous.sh --hours 24         # back-to-back ticks for N hours
bash research/scripts/leaderboard.sh --top 15              # sortable leaderboard
bash research/scripts/queue-strategy.sh --code LSR --interval 1h --hypothesis "..." --sweep '...' --iter-budget N
bash research/scripts/reconstruct-strategy.sh <iter_id>    # print params + git commit + curl recipe
bash research/scripts/burn-queue-load.sh                   # one-shot 11 sweeps for burn windows
```

### Test
```bash
./gradlew test
./gradlew test --tests "com.example.YourTest"
```

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
- **Do not modify the scope of `LiveTradingDecisionExecutorService`'s `simulated` check.** It only diverts `OPEN_LONG`/`OPEN_SHORT`. Expanding to CLOSE_*/UPDATE would strand real positions on demote. (Phase 1 audit Bug 1.)
- **Do not remove `@Profile("!research")` from `BlackheartApplication`** or `@Profile("research")` from `BlackheartResearchApplication`. Both are required to prevent JpaRepositories collision when both main classes are present.
- **Do not remove `tr -d '[:space:]'`** from `research-tick.sh` psql captures. It strips Windows CR characters; without it, backtest submission fails with HTTP 500 (`JsonParseException: Illegal CTRL-CHAR code 13`).
- **Do not bypass `deploy-strategy.sh`'s 4-hour frequency cap** by overriding `STRATEGY_GEN_MIN_INTERVAL_SECONDS`. The cap exists to prevent restart thrashing on the trading JVM.

## Operations Telemetry (V23+)

Both JVMs expose Spring Boot Actuator + Micrometer Prometheus for the frontend `/research` dashboard.

### Endpoints
- `GET /actuator/health` — liveness, public on the upgrade path? **No** — gated `hasRole('ADMIN')`. The frontend service-health panel polls this from an admin session.
- `GET /actuator/info` — build / git info.
- `GET /actuator/metrics` — list of metric names.
- `GET /actuator/metrics/{name}` — single metric (e.g. `jvm.memory.used`, `jvm.gc.pause`, `process.uptime`, `system.cpu.usage`, `process.cpu.usage`, `jvm.threads.live`).
- `GET /actuator/prometheus` — full Prometheus scrape (admin-only). Reserved for future Prometheus pull; the dashboard uses `/metrics/{name}` directly.

### Why admin-gated
Actuator surfaces internal state — heap addresses, thread dumps, env vars when `info` is wide. Production exposure without auth is a known foot-gun. Both JVMs route through the same `SecurityConfig`, so one rule covers both.

### Strategy Promotion endpoints (admin-only)
`StrategyPromotionController` already exists at `/api/v1/strategy-promotion/*`:
- `POST /{accountStrategyId}/promote` — atomic state flip + log row (uses `StrategyPromotionService`).
- `GET /{accountStrategyId}/state` — current state derived from `enabled` + `simulated`.
- `GET /{accountStrategyId}/history` — promotion log for the strategy.
- `GET /{accountStrategyId}/paper-trades` — paper-trade run rows for paper-trade-state strategies.
- `GET /recent?limit=50` — **added V23** — cross-strategy feed for the dashboard "Recent promotions" panel.

`AccountStrategy` row exposes `simulated` on the wire so the frontend can derive the state (`enabled` + `simulated` → RESEARCH / PAPER_TRADE / PROMOTED / DEMOTED). Without `simulated`, the dashboard cannot distinguish PROMOTED from DEMOTED.

## Research Orchestrator (FastAPI, V28+)

A separate Python/FastAPI service in `research-orchestrator/` (port 8082) is the **agent-facing front door** for research. It replaces the bash drivers (`research-tick.sh`, `analyze-run.py`, `walk-forward.sh`, `queue-strategy.sh`) with a typed, transactional, agent-friendly HTTP API. The bash scripts remain as operator fallback but should not be the primary path going forward.

### Topology
```
quant-researcher agent / cron / dashboard
            │   X-Orch-Token + X-Agent-Name [+ Idempotency-Key]
            ▼
research-orchestrator (uvicorn, single worker, 127.0.0.1:8082)
   │ asyncpg as blackheart_research                    │ httpx
   ▼                                                    ▼
PostgreSQL (V28: idempotency_record, V13: research_queue)   Research JVM (8081)
```

### What it owns
- **Queue claim** — `SELECT ... FOR UPDATE SKIP LOCKED` on `research_queue`; stuck-row reaper for crashed ticks.
- **Backtest submit + poll** — `POST /backtest` to research JVM, polls `backtest_run.status` to terminal state.
- **Statistical analysis** — bootstrap 95% CI on profit factor, PSR (Bailey & López de Prado 2014), slippage haircut at +5/+10/+20/+50bps, regime stratification.
- **Walk-forward validation** — 6-fold rolling window, stability verdict ∈ {ROBUST, INCONSISTENT, INSUFFICIENT_EVIDENCE, OVERFIT, NO_EDGE}. ROBUST is the gate for graduation.
- **Idempotent retries** — `Idempotency-Key` header dedupes across restarts via Postgres-backed `idempotency_record` (V28).

### Endpoints (agent contract)
| Method | Path | Purpose |
|---|---|---|
| GET  | `/healthz`, `/readyz` | Liveness / readiness (public) |
| GET  | `/agent/playbook` | Discoverable contract — auth, headers, error envelope, capabilities, recipes (public) |
| GET  | `/agent/state` | One-shot snapshot (db_ok, jvm_ok, notes, next_actions) |
| GET  | `/queue` / `/queue/{id}` | Browse queue; cursor-paginated |
| POST | `/queue` | Enqueue a sweep (Idempotency-Key honoured) |
| POST | `/queue/{id}/cancel` | Park a PENDING/RUNNING row with a reason |
| GET  | `/iterations` / `/iterations/{id}` / `/leaderboard` | Read research_iteration_log |
| GET  | `/journal` / `/journal/{id}` | Read research_journal |
| POST | `/tick` | Run one iteration end-to-end (claim → submit → poll → analyse → write iteration_log → decide). Up to ~30 min synchronous |
| POST | `/walk-forward` | 6-fold validation. Up to ~3 hours synchronous; agent calls deliberately after a SIGNIFICANT_EDGE tick parks the queue |

### Quant-grade gate (V11+ statistical contract)
A tick returns `verdict=PASS` only when **all** of: n_trades ≥ 100, PF 95% CI lower > 1.0, +20bps slippage net PnL > 0, statistical_verdict = SIGNIFICANT_EDGE. SIGNIFICANT_EDGE iterations park their queue and emit a `next_action` pointing at `POST /walk-forward`; the iteration is only graduation-eligible after walk-forward returns `stability_verdict=ROBUST`.

### Operating notes
- **Loopback only.** Binds to `127.0.0.1`; the shared-secret token is defense-in-depth, not perimeter.
- **Single uvicorn worker.** Claim loop and idempotency assume one process — scale by sharding the queue, not by adding workers.
- **Migrations live here, not there.** The orchestrator is a DML-only client of the trading-JVM schema. New tables for orchestrator state get a Flyway migration in `blackheart/src/main/resources/db/flyway/` (V28 `idempotency_record` is an example), not a separate migration system.
- **Profile = prod refuses dev secrets.** `Settings.assert_prod_safe` blocks startup with the dev sentinel token or `dev_bypass` JVM auth.
- **Idempotency store** is in-memory in dev, Postgres-backed in prod (`PostgresIdempotencyStore` reads `idempotency_record` V28).

## Autonomous Research Loop (agent contract)

The quant-researcher agent operates **independently**. Its standing goal is *find the next profitable strategy* — net ≥10%/yr after fees + slippage, walk-forward `ROBUST`. The operator does **not** hand the agent hypotheses; the agent picks them.

### What the agent decides on its own
- **What to research next.** Reads `/leaderboard` + `/agent/state` + `research_iteration_log` to identify coverage gaps (archetypes not yet tested, intervals not yet swept, regimes not yet stratified) and picks the next hypothesis.
- **Sweep design.** Archetype, parameter ranges, interval, iter budget.
- **When to escalate.** A `SIGNIFICANT_EDGE` tick triggers `/walk-forward` on the next loop without operator approval.
- **When to abandon.** `NO_EDGE` / `MARGINAL` after a reasonable sweep budget → journal the hypothesis as discarded and move on. Do not keep tuning a dead strategy (see `strategy_research_lessons` discipline).
- **Journaling.** Every hypothesis pre-registered in `research_journal` *before* the sweep — rationale, sweep config, expected edge. Then the verdict, then the decide-to-escalate-or-abandon. The audit trail is what makes autonomous operation reviewable.

### What the agent does NOT decide
- **Promotion to real capital.** `ROBUST` walk-forward is the **gate**, not the trigger. The agent stops at "graduation candidate" and journals it. The operator runs `POST /api/v1/strategy-promotion/{id}/promote` to flip `RESEARCH → PAPER_TRADE` and later `→ PROMOTED`.
- **Statistical thresholds.** V11 gate (n ≥ 100, PF 95% CI lower > 1.0, +20bps net positive, PSR ≥ 0.95) is fixed. Adjusting them = moving the goalposts.
- **Methodology constants.** Walk-forward fold count, slippage haircut grid, IS/OOS/holdout split — operator-controlled.
- **Trading JVM, frontend, live infrastructure, DB schema, V14 roles, idempotency contract.** Edit authority is scoped to `research-orchestrator/` and `research/specs/`. Full out-of-bounds list in `.claude/agents/quant-researcher.md`.
- **Protected strategies.** `LSR`, `VCB`, `VBO` and their param tables (`lsr_strategy_param`, `vcb_strategy_param`, `vbo_strategy_param`) are untouchable baselines. Research that would alter their params, gates, or definitions is out of scope.

### Cadence
- **Tick loop.** Agent's CronCreate fires every N hours (default 6). Each fire: one `/tick` if there's queue depth, or one fresh hypothesis enqueue if the queue is empty.
- **Walk-forward gate.** A queue parked at `SIGNIFICANT_EDGE` triggers `/walk-forward` on the next agent fire.
- **Spec generation cap.** 4-hour minimum between spec-driven strategy generations (`/tmp/last-strategy-gen.txt`) to prevent restart thrashing if codegen lands a compile failure.

### Anti-overfitting discipline (built into the loop)
Every iteration is a trial in a multiple-testing sense. The agent must:
1. **Pre-register** the hypothesis in `research_journal` *before* the sweep, not after the verdict.
2. Treat each sweep cell as a trial — Harvey-Liu-Zhu trial scaling is implicit in PSR's data-mining adjustment; the more cells, the higher the implicit bar.
3. Never re-run an identical param grid hoping for a different verdict — that's p-hacking.
4. Holdout discipline: most recent N% of data is reserved for the final IS/OOS check; never tuned on. The walk-forward folds rotate; the holdout doesn't.

### When the agent should stop and ask
- Hypothesis needs data not yet plumbed (e.g. ETHUSDT pre-Phase-3, funding rate pre-Phase-4).
- Walk-forward returns `ROBUST` → graduation candidate; the agent journals it and stops. Operator promotes.
- An iteration suggests a methodology change (different fold count, different slippage assumption) — the agent flags it in the journal but does not implement.
- A sweep would touch a protected strategy (LSR/VCB/VBO).

### Frequency cap rationale
The cap is **not** a rate limit on research; it's a guardrail on **deploys to the trading host**. The agent can run many backtests per hour (cheap, isolated to research JVM). What it cannot do is regenerate spec-driven Java + restart the JVM more than every 4 hours, because each restart momentarily blanks the live trading process if the trading and research JVMs ever get redeployed together.

## Frontend `/research` Dashboard (consumer of telemetry)

Single page at `/research` — admin-only, 7 panels: service health, JVM telemetry per JVM (heap, GC, threads, uptime, CPU%), scheduler status, sweep activity, promotion candidates table with promote/demote/reject dialog, recent promotions feed, research log tail. Telemetry polls every 5s; everything else 30s. Sweeps list and log keep their existing URLs (`/research/sweeps`, `/research/log`).

## Quant-Grade Roadmap

The architecture is now **quant-grade in code structure** (statistical rigor, audit trail, decoupled JVMs, promotion pipeline, formal spec language) but **single-asset in data plane** (BTC only). The progressive path to full quant-grade:

### Q1 — Data breadth (highest ROI)
1. **Phase 3** (1-2 weeks): plumb ETH/USDT — parameterize `BinanceWebSocketClient.SYMBOL`, multi-symbol `MarketDataService`/`FeatureStoreService` backfill, multi-asset walk-forward. Unlocks ~60-70% of strategy archetypes physically impossible today (pairs, cross-asset momentum).
2. **Phase 4** (~1 week): funding-rate ingestion. New `funding_rate_history` table, `FundingRateService`, FeatureStore columns (`fundingRate8h`, `fundingRate7dAvg`, `fundingRateZ`), new archetype `funding_carry.java.tmpl`.
3. **Phase 5** (~1 week): multi-window backtesting. `analyze-run.py --windows`, new `ROBUST_CROSS_WINDOW` verdict requiring ≥80% windows positive.

### Q2 — Operational maturity
4. **Phase 6** (~2 weeks): frontend leaderboard. Web UI for sortable strategy list, journal browser, click-through reproduction. Compounds in value as iteration_log grows.
5. **Phase 7** (~1-2 weeks): monitoring + alerting. Prometheus metrics on both JVMs, Slack/Telegram alerts on trading downtime, P&L deviation, slippage anomalies, walk-forward verdict shifts on PROMOTED strategies.
6. **Phase 8** (~3-4 weeks): risk model. `strategy_returns_daily` table, rolling correlation matrix, risk-parity allocation, factor-exposure tracking.

### Q3 — Search scale
7. **Phase 9** (~3-4 weeks): population-based / evolutionary search. 50-100 strategy specs maintained as a generation. Mutation + crossover operators. Fitness = walk-forward stability × Sharpe − complexity_penalty. Backtest worker pool for parallelization.
8. **Phase 10** (~2 weeks): open interest + basis data. OI from `/fapi/v1/openInterestHist`, basis = `(perp − spot)/spot`. New archetypes utilizing perp-market structure.

### Skip / deprioritize
- Full Gradle module split (Stage B2-final) — 90% of value already shipped via tradingBootJar exclusions.
- DB role separation (Phase 1 Bug 3) — defense-in-depth; Phase 1 process decoupling already prevents the scenarios it'd catch.
- Real-time tick data + microstructure — different game; 5m bars are right granularity for current archetype catalog.
- ML/prediction integration — codebase has hooks but ML alpha on retail-quality data is hard; archetypal strategies first.

### Decision criteria after Q1
- If Phase 3 (multi-asset) yields candidates that pass walk-forward → keep building Q2.
- If alpha plateaus → reassess: is the issue data plane (Phase 4/10) or methodology (Phase 9)?
- If existing live book (LSR/VCB/VBO) starts decaying → focus shifts to monitoring (Phase 7) + risk (Phase 8) before adding new strategies.

The single biggest expected-ROI move from today is **Phase 3 (ETH plumbing)**. Architecture supports it; data plumbing is ~half-day per new instrument; unlocks new strategy categories that are physically untestable on BTC-only.