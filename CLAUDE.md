# Blackheart — Algorithmic Trading Platform

Java/Spring Boot algo trading on Binance: live trading + backtesting.

> **Future-state**: see `docs/PARAMETRIC_ENGINE_BLUEPRINT.md`. Hand-written Java strategies (LSR, VCB, VBO) are the proven legacy baseline — explicitly preserved.

## Where to find more (read on demand — not loaded by default)

| Topic | File |
|---|---|
| Topology, modules, models, controllers, design patterns | `docs/agent-context/ARCHITECTURE.md` |
| Flyway history (V14–V40), promotion pipeline state machine | `docs/agent-context/MIGRATIONS.md` |
| Build / run / test / research-ops recipes | `docs/agent-context/COMMANDS.md` |
| Working rules, parity invariants, point-in-time discipline, full strategy table | `docs/agent-context/WORKING_RULES.md` |
| Two-JVM deployment runbook | `research/DEPLOYMENT.md` |
| DB role separation | `research/DB_USER_SEPARATION.md` |
| Spec language (Phase 2) | `research/specs/SCHEMA.md` |
| Parametric engine blueprint (M1+) | `docs/PARAMETRIC_ENGINE_BLUEPRINT.md` |
| Research orchestrator (FastAPI agent contract) | `../research-orchestrator/CLAUDE.md` |

## Tech stack
Java 21, Spring Boot 3.3.5, Gradle, Hibernate 6, PostgreSQL, Redis, Kafka, Flyway, TA4j, JWT, Binance REST+WS.

## Topology (V14+)
Two JVMs share Postgres. Trading JVM (8080) owns Binance + live; Research JVM (8081) owns backtest + research. Trading JVM never participates in research; research crashes can't disturb live. Migrations live in Trading JVM. See `ARCHITECTURE.md`.

## Active strategies — current status
- **Production (untouchable):** LSR, VCB, VBO — each ~+20%/yr live.
- **Research:** TPR (not yet profitable).
- **Discarded:** DCT (10%/yr no margin), BBR (NO_EDGE), CMR (never traded).
- **Profitability bar:** 10%/yr net after fees+slippage. Below that = scrap or shelve.
- See `WORKING_RULES.md` for the full table + discard reasons.

## Data plane
- **Backtest/research:** BTCUSDT + ETHUSDT plumbed end-to-end (Phase 3 shipped 2026-05-01), 4 intervals (5m/15m/1h/4h), ~17 months min.
- **Live:** BTCUSDT only (`BinanceWebSocketClient` single-bean). Multi-symbol live needs trading-JVM restart.
- **Other symbols (SOL, BNB, …):** NOT plumbed. Backfill first via `MarketDataService` / `HistoricalDataService`.

## Current head
- **Flyway:** V40 (definition-scope promotion). Recent: V34–V37 funding-rate, V38 cross-window, V39 alert_event, V40 definition-scope. See `MIGRATIONS.md` for catalog.

## Stat-rigor gate (V11+)
Tick returns `verdict=PASS` only when **all**: n_trades≥100, PF 95% CI lower>1.0, +20bps slippage net PnL>0, statistical_verdict=SIGNIFICANT_EDGE. SIGNIFICANT_EDGE parks queue + emits `next_action` for `/walk-forward`; only graduation-eligible after `stability_verdict=ROBUST`.

## Working rules (headline — full text in `WORKING_RULES.md`)
- **Targeted minimal changes** over refactors. Don't rename DTOs/columns or change public APIs unless asked.
- **Trading-logic safety:** preserve live↔backtest parity; respect Binance lot/precision/min-notional/balance.
- **Code change format:** root cause → solution → files → risks → diff → test plan.
- **Persistence:** `@JdbcTypeCode(SqlTypes.JSON)` for JSONB (NOT `AttributeConverter`); evict Redis cache via `afterCommit()`.
- **Migrations are immutable once applied.** New schema = new V<N>.
- **If uncertain:** ask whether the user wants minimal patch or refactor.

## Do Not — hard list
- Don't rewrite architecture unless requested.
- Don't replace TA4j-based logic without justification.
- Don't bypass risk checks, fee handling, or fill simulation.
- Don't introduce hidden behavior changes in strategy execution.
- Don't make broad package moves or rename classes unnecessarily.
- Don't use `@Convert(converter = JsonMapConverter.class)` for JSONB — use `@JdbcTypeCode(SqlTypes.JSON)`.
- Don't evict Redis cache inside `@Transactional` — use `afterCommit()` via `TransactionSynchronizationManager`.
- **Don't modify scope of `LiveTradingDecisionExecutorService`'s `simulated` check.** Diverts only `OPEN_LONG`/`OPEN_SHORT`; expanding to CLOSE_*/UPDATE strands real positions on demote. (Phase 1 audit Bug 1.) V40 broadened the *trigger* to `definition.simulated OR accountStrategy.simulated` but kept the OPEN_*-only scope intact — preserve that.
- **Don't remove `@Profile("!research")` from `BlackheartApplication`** or `@Profile("research")` from `BlackheartResearchApplication`. Both required to prevent JpaRepositories collision.
- **Don't remove `tr -d '[:space:]'`** from `research-tick.sh` psql captures — strips Windows CR; without it backtest submission fails HTTP 500 (`JsonParseException: Illegal CTRL-CHAR code 13`).
- **Don't bypass `deploy-strategy.sh`'s 4-hour cap** by overriding `STRATEGY_GEN_MIN_INTERVAL_SECONDS`. Prevents restart thrashing on trading JVM.
- **Don't touch protected production strategies** (LSR/VCB/VBO) without explicit user instruction. Their default params, defaults() factory, applyOverrides(), and entry/exit logic are frozen.

## Research orchestrator (FastAPI, V28+, port 8082)
Loopback-only Python service in `research-orchestrator/`. The agent-facing front door — replaces bash `research-tick.sh` / `walk-forward.sh` / `queue-strategy.sh`. Full contract via `GET /agent/playbook`. See `../research-orchestrator/CLAUDE.md`.

## Autonomous research loop (agent contract — headline)
quant-researcher operates **independently**. Standing goal: find next profitable strategy ≥10%/yr after fees+slippage, walk-forward `ROBUST`. Operator does NOT hand hypotheses.

**Agent decides:** what to research, sweep design, when to escalate (SIGNIFICANT_EDGE → walk-forward), when to abandon (NO_EDGE/MARGINAL → journal + move on), journaling discipline (pre-register, then verdict, then escalate-or-abandon).

**Agent does NOT decide:** promotion to real capital (ROBUST = gate, not trigger); statistical thresholds (V11 fixed: n≥100, PF 95% CI lower>1.0, +20bps net positive, PSR≥0.95); methodology constants; anything outside `research-orchestrator/` and `research/specs/`; protected strategies.

**Cadence:** CronCreate every N hours (default 6); each fire = one `/tick` if queue depth, else fresh hypothesis enqueue.

**Anti-overfitting:** pre-register before sweep; HLZ-scaled DSR for multi-testing (V11+); never re-run identical grid hoping for different verdict; holdout = most recent N%, never tuned on.

**Stop and ask:** unplumbed data; ROBUST graduation candidate; methodology change; touching protected strategy.

## External services
Binance primary, Tokocrypto secondary. FastAPI :8000 ML/DL. Node.js :3000.
