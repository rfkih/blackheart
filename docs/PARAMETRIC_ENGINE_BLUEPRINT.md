# Parametric Trading Engine — Architecture Blueprint

> **Status**: Design — not yet implemented (as of 2026-04-29).
> **Owner**: Operator-led; agent-implemented.
> **Scope**: Long-form blueprint for the future of Blackheart's strategy execution layer.
>
> This document is the **single source of truth** for the parametric trading engine. Subsequent CLAUDE.md updates, milestone PRs, and operator runbooks reference back to this file. When details here conflict with code, code wins; this doc is then updated to match.

---

## 1. Mission

Replace the per-strategy Java-class development model with a **specification-driven engine**: any new strategy is authored as JSON/YAML spec, validated against an archetype schema, persisted as a database row, and executed by a single `StrategyEngine` class. Existing battle-tested Java strategies (LSR, VCB, VBO) remain untouched and continue to deliver their proven edge.

The end state: research and trading converge on a single API. Authoring a new strategy never requires a Java file, a Gradle compile, or a JVM restart. Only adding a new **archetype** (a structurally novel strategy shape) requires engineering work.

---

## 2. Vision (1-page summary)

```
                          ┌────────────────────────────────────┐
                          │       Blackheart Trading Service    │
                          │            (port 8080)              │
                          ├────────────────────────────────────┤
                          │                                     │
                          │  StrategyExecutorFactory            │
                          │     │                               │
                          │     ├── LsrStrategyService          │
                          │     ├── VcbStrategyService          │
                          │     ├── VboStrategyService          │
                          │     │   (legacy Java — proven)      │
                          │     │                               │
                          │     └── StrategyEngine ◄── ALL new  │
                          │           ▲                strategies│
                          │           │                          │
                          │     ┌─────┴─────────────────────┐    │
                          │     │ strategy_definition       │    │
                          │     │   spec_jsonb (per code)   │    │
                          │     └───────────────────────────┘    │
                          │     ┌───────────────────────────┐    │
                          │     │ strategy_param            │    │
                          │     │   param_overrides JSONB   │    │
                          │     │   (per account_strategy)  │    │
                          │     └───────────────────────────┘    │
                          └────────────────────────────────────┘
                                        │
                                        ▼
                          ┌────────────────────────────────────┐
                          │   LiveTradingDecisionExecutor       │
                          │   (UNTOUCHED safety perimeter)      │
                          │   • simulated flag                  │
                          │   • kill switches                   │
                          │   • audit log                       │
                          │   • Binance order placement         │
                          └────────────────────────────────────┘
```

**Authoring lifecycle**:

```
operator/agent
    │
    │  POST /api/v1/strategy-definitions
    │  { code: "MY_STRAT_v1", archetype: "...", spec: {...} }
    ▼
strategy_definition.spec_jsonb (validated)
    │
    │  POST /api/v1/account-strategies
    │  { strategyCode, accountId, simulated: true }
    ▼
account_strategy row created
    │
    │  POST /api/v1/backtest
    │  { accountStrategyId, ... }
    ▼
backtest validates on history
    │
    │  POST /api/v1/strategy-promotion/{id}/promote
    │  { toState: "PROMOTED" }
    ▼
live capital, audited
```

**Zero IDE. Zero compile. Zero restart.**

---

## 3. Scope

### In scope

- A `StrategyEngine` Java class that interprets JSON specs at runtime
- A unified `strategy_param` table replacing `lsr_strategy_param`, `vcb_strategy_param`, `vbo_strategy_param`
- A `strategy_definition.spec_jsonb` column carrying full archetype-shaped specs
- 4 initial archetypes: `mean_reversion_oscillator`, `trend_pullback`, `donchian_breakout`, `momentum_mean_reversion`
- Rule, sizing, and exit primitive libraries
- Hot reload via Postgres LISTEN/NOTIFY
- Spec validator with archetype schema
- Spec trace observability
- Frontend dynamic param form replacing per-strategy hand-coded forms

### Out of scope (deferred)

- LIMIT / STOP order types (engine emits MARKET only initially)
- Cross-asset features (pairs trading) — depends on Phase 3 ETH plumbing
- Risk-parity / Kelly sizing — depends on Phase 8 portfolio layer
- ML model integration — escape hatch only (`custom_signal` primitive)
- Order-book microstructure — different system; engine has no L2 data
- Migration of LSR/VCB/VBO to specs — they stay as Java permanently

### Boundary: what stays in code, never in spec

These are part of the **safety perimeter** and cannot be expressed as spec parameters:

1. The simulated-flag check (`LiveTradingDecisionExecutorService` line ~120 today)
2. Kill-switch enforcement (DD threshold, max consecutive losses tripwire)
3. Audit event writes (`audit_event` table)
4. Reconciliation loop (position vs exchange sync)
5. Order state machine (Open → Filled → PartialFill → Canceled)
6. Schema migrations
7. Spec ↔ engine version compatibility logic

A buggy spec must NEVER be able to bypass these. The engine emits `Decision` objects; everything below the engine in the call chain is the perimeter.

---

## 4. Coexistence with existing Java strategies

LSR, VCB, VBO continue to exist as their current classes. The engine sits **alongside** them, routed through the same `StrategyExecutorFactory`:

```java
@Component
public class StrategyExecutorFactory {

    private final Map<String, StrategyExecutor> javaExecutors;
    private final StrategyEngine engine;
    private final StrategyDefinitionService definitions;

    public StrategyExecutor get(String code) {
        // 1. Hand-written Java executor takes precedence (LSR, VCB, VBO, etc.)
        StrategyExecutor java = javaExecutors.get(code.toUpperCase());
        if (java != null) {
            return java;
        }

        // 2. Spec-driven definition exists?
        if (definitions.existsByCode(code)) {
            return engine;  // single engine instance handles all spec strategies
        }

        // 3. Unknown
        throw new UnknownStrategyException(code);
    }
}
```

The `StrategyEngine.evaluate(ctx)` resolves the spec by `ctx.getStrategyCode()` at evaluation time — one engine instance, many strategies.

**Migration policy**: existing strategies stay as Java forever. Do not migrate LSR/VCB/VBO to specs even if they fit an archetype. The risk of parity drift on a profitable live strategy outweighs the architectural elegance.

---

## 5. The Robustness Contract

Ten invariants the engine MUST honor. These are non-negotiable. Any milestone that breaks an invariant is not done.

| # | Invariant | Mechanism |
|---|---|---|
| 1 | Engine errors cannot crash JVM | Per-rule try/catch in `RuleEvaluator`; engine returns NO_TRADE on uncaught exception |
| 2 | One strategy's spec failure cannot affect others | Each `accountStrategyId` evaluated independently; no shared mutable state across evaluations |
| 3 | Live and backtest produce identical decisions | Single `StrategyEngine` class used by both; no `if (live)` branches; same spec input → same output |
| 4 | Cannot bypass safety perimeter | Engine emits `Decision` only; `LiveTradingDecisionExecutorService` retains all simulated/kill-switch/audit logic untouched |
| 5 | Bad specs rejected at write, not at evaluation | `StrategyDefinitionController` validates against archetype JSON Schema before INSERT/UPDATE |
| 6 | Every decision traceable | `SpecTraceLogger` records: rules evaluated, results, sizing inputs, final decision. Sampled in live, full in backtest. |
| 7 | Hot reload doesn't drop signals | Atomic cache swap via `AtomicReference<Map<String,StrategySpec>>`; in-flight evaluations finish on snapshot taken at start |
| 8 | Spec version mismatch fails closed | Engine refuses to evaluate a spec whose archetype version is unsupported; logs + skips the strategy; alerts operator |
| 9 | Repeated spec errors trip kill switch | `EngineMetrics` tracks per-strategy error rate; > N errors / M bars → auto-trip `is_kill_switch_tripped` on the `account_strategy` row |
| 10 | Every decision replayable | Spec snapshot persisted alongside each `BacktestRun` and trade open; given (snapshot, features at t), decision is deterministic |

These are tested per milestone. Failing any of these in CI blocks the merge.

---

## 6. Architecture

### 6.1 Component diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                             StrategyEngine                                │
│                                                                           │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                         evaluate(ctx)                             │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                                  │                                        │
│         ┌────────────────────────┼────────────────────────┐              │
│         ▼                        ▼                        ▼              │
│   ┌───────────┐           ┌────────────┐          ┌──────────────┐       │
│   │SpecResolver│           │RuleEvaluator│          │SpecTraceLogger│       │
│   │           │           │             │          │              │       │
│   │• cache    │           │• AND/OR/NOT │          │• per-bar log │       │
│   │• hot      │           │• N_OF_M     │          │• sampled live│       │
│   │  reload   │           │• per-rule   │          │• full back-  │       │
│   │• version  │           │  try/catch  │          │  test trace  │       │
│   │  adapter  │           │             │          │              │       │
│   └───────────┘           └─────────────┘          └──────────────┘       │
│                                  │                                        │
│         ┌────────────────────────┼────────────────────────┐              │
│         ▼                        ▼                        ▼              │
│   ┌──────────────┐         ┌────────────┐          ┌──────────────┐      │
│   │ RuleLibrary  │         │SizingLibrary│          │ ExitManager  │      │
│   │              │         │             │          │              │      │
│   │~25 entry/    │         │5 sizing     │          │SINGLE/TP1/   │      │
│   │exit rule     │         │methods      │          │TP2/RUNNER/   │      │
│   │primitives    │         │             │          │TIME/COND     │      │
│   └──────────────┘         └─────────────┘          └──────────────┘      │
└──────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
                    ┌────────────────────────────┐
                    │     EngineMetrics          │
                    │ • eval latency histogram   │
                    │ • per-strategy error rate  │
                    │ • kill-switch trip trigger │
                    └────────────────────────────┘
```

### 6.2 Evaluation pipeline

```
1. Resolve spec from ctx.strategyCode  [SpecResolver]
2. Validate version compatibility       [SpecVersionAdapter]
3. Determine direction permissions      [allowLong/allowShort × spec.direction]
4. If position open:
     → ExitManager.manage(spec, ctx)
     → emit CLOSE / UPDATE / no-op
5. Else (position flat):
     → For each allowed direction:
         → RuleEvaluator.evaluate(spec.entry[dir], ctx)
         → if true:
             → SizingLibrary.compute(spec.sizing, ctx)
             → emit OPEN_LONG / OPEN_SHORT
             → break
6. Return Decision (with snapshot of spec for replay)
7. SpecTraceLogger.record(spec, ctx, decision)
8. EngineMetrics.recordEval(strategy_code, latency, error?)
```

All steps wrapped in master try/catch returning `NO_TRADE` on uncaught error.

### 6.3 Hot reload flow

```
┌──────────────┐         ┌─────────────┐         ┌──────────────┐
│ Operator     │         │ Postgres    │         │ Trading JVM  │
│ updates spec │────────▶│             │         │              │
│ via HTTP     │  UPDATE │ trigger:    │         │ SpecResolver │
│              │         │ NOTIFY      │         │   .listener  │
│              │         │ "spec_chg"  │────────▶│              │
│              │         │             │ LISTEN  │ atomic swap  │
└──────────────┘         └─────────────┘         │ in cache     │
                                                  │              │
                                                  │ next bar:    │
                                                  │ new spec     │
                                                  │ used         │
                                                  └──────────────┘
```

In-flight evaluations capture spec snapshot at start; reload affects only subsequent calls. No mid-evaluation state change.

---

## 7. Specification schema

### 7.1 Top-level shape

```yaml
# strategy_definition.spec_jsonb (one row per strategy code)

code: BBR_v2                            # unique strategy code (string, [A-Z0-9_]+)
archetype: mean_reversion_oscillator    # which archetype handler to use
archetype_version: 2                    # archetype schema version (engine compat)
description: "Bollinger reversal v2"    # operator-readable

# === Symbol + timeframe ===
symbol: BTCUSDT
interval: 1h
bias_interval: 4h                       # null = no bias filter
require_previous_feature_store: true    # enrichment hint for backtest engine

# === Direction permissions (at strategy level; account_strategy
#     can further restrict via allowLong / allowShort) ===
allow_long: true
allow_short: false

# === Entry rules (archetype-specific schema) ===
entry:
  long:
    type: ALL_OF                        # or ANY_OF, N_OF_M
    rules:
      - { type: bollinger_extreme, side: lower, period: 20, std: 2.0 }
      - { type: rsi_extreme, side: oversold, period: 14, threshold: 25 }
      - { type: candle_pattern, kind: bullish_reversal }
      - { type: regime_filter, indicator: adx, op: '<', threshold: 25 }
  short: null

# === Exit rules ===
exit:
  structure: SINGLE                     # SINGLE | TP1_RUNNER | TP1_TP2_RUNNER | RUNNER_ONLY | etc.
  stop_loss:
    type: atr_based
    multiplier: 1.5
    period: 14
  take_profit:
    type: r_multiple
    target: 2.0                         # 2R fixed take-profit
  trail: null                           # only relevant if structure includes RUNNER
  early_exit:
    - { type: time_limit, max_bars: 100 }
    - { type: condition, rule: { type: rsi_extreme, side: overbought, threshold: 70 }, when: profitable }

# === Sizing ===
sizing:
  method: risk_based                    # see Sizing Library
  risk_pct: 1.5                         # % of book at stop distance
  max_position_usdt: 500
  min_position_usdt: 5

# === Risk gates ===
risk:
  max_open_positions: 1
  max_drawdown_kill_pct: 25
  max_consecutive_losses: 5
  max_position_size_pct: 40             # cap as % of book

# === Lifecycle (managed by promotion service, NOT operator-edited directly) ===
lifecycle:
  state: PAPER_TRADE                    # RESEARCH | PAPER_TRADE | PROMOTED | DEMOTED
  promoted_at: null
  evidence_run_id: null

# === Metadata ===
created_at: 2026-04-29T10:00:00
created_by: operator@blackheart
updated_at: 2026-04-29T10:00:00
schema_version: 1                       # spec doc schema version (top-level)
```

### 7.2 Archetype-required fields

Each archetype declares which fields its `entry` block must contain. Validator enforces.

| Archetype | Required entry rules |
|---|---|
| `mean_reversion_oscillator` | bollinger_extreme OR rsi_extreme; candle_pattern; regime_filter |
| `trend_pullback` | ema_stack; pullback_signal; trend_filter |
| `donchian_breakout` | donchian_extreme; volume_filter; breakout_strength |
| `momentum_mean_reversion` | momentum_signal; ema_distance; mean_reversion_trigger |

Archetype schemas live in `research/templates/<archetype>.schema.json` (JSON Schema) and are loaded by the validator at startup.

---

## 8. Archetype catalog (initial 4)

### 8.1 mean_reversion_oscillator

**Logic**: enter when price is at an extreme of an oscillator and a reversal candle confirms; exit at fixed target or stop.

**Examples it can express**:
- BBR (Bollinger Band reversal — currently a Java class)
- RSI mean-reversion variants
- Stochastic extremes
- Z-score reversals (when statistical primitives added)

**Required slots**:
- `entry.{long|short}.rules`: oscillator extreme + reversal candle + regime filter
- `exit.structure`: typically SINGLE
- `exit.take_profit`: r-multiple or fixed price target
- `exit.stop_loss`: atr-based

### 8.2 trend_pullback

**Logic**: in a trending regime, wait for a pullback to a moving average, then enter on resumption signal.

**Examples**:
- TPR (Trend Pullback — currently a Java class)
- EMA21 pullback
- Multi-timeframe pullback with HTF bias

**Required slots**:
- `entry`: trend filter + pullback condition + resumption trigger
- `exit.structure`: typically TP1_RUNNER or RUNNER_ONLY
- `exit.trail`: chandelier or atr-based

### 8.3 donchian_breakout

**Logic**: enter on N-period high/low breakout with volume + volatility confirmation.

**Examples**:
- DCT (Donchian Channel Trend — discarded but can be re-expressed)
- ORB (Opening Range Breakout — discarded but expressible)
- Channel breakout variants

**Required slots**:
- `entry`: donchian_extreme + volume_filter + breakout_strength
- `exit.structure`: TP1_RUNNER or RUNNER_ONLY
- `exit.trail`: typical 2.5-ATR chandelier

### 8.4 momentum_mean_reversion

**Logic**: enter when momentum is strong but price has stretched too far from a moving average; bet on partial reversion.

**Examples**:
- MMR-style strategies
- "Reversion to EMA200" plays
- Statistical mean-reversion with momentum filter

**Required slots**:
- `entry`: momentum signal + distance-from-MA + reversion trigger
- `exit.structure`: typically SINGLE or TP1_RUNNER
- `exit.take_profit`: distance-to-MA-based

---

## 9. Rule library

The complete catalog of entry/exit rule primitives. Each is a pure function `(features, ctx) → boolean`.

### 9.1 Indicators (read-only feature lookups)

| Primitive | Schema | Notes |
|---|---|---|
| `rsi_extreme` | `{ side: oversold\|overbought, period: int, threshold: number }` | Reads `feature_store.rsi_<period>` |
| `bollinger_extreme` | `{ side: lower\|upper\|either, period: int, std: number }` | BB bands from feature store |
| `keltner_extreme` | `{ side, period, multiplier }` | KC bands |
| `donchian_extreme` | `{ side: high\|low, period: int }` | Donchian channel |
| `ema_stack` | `{ fast: int, slow: int, direction: bullish\|bearish }` | EMA alignment check |
| `ema_distance` | `{ period: int, op: '>'\|'<'\|'>='\|'<=', threshold_pct: number }` | Price distance from EMA in % |
| `macd_signal` | `{ side: bullish\|bearish }` | MACD line vs signal line |
| `adx_threshold` | `{ op: '>'\|'<', threshold: number }` | Trend strength filter |
| `atr_band` | `{ op, threshold_pct: number }` | Volatility regime |
| `relative_volume` | `{ period: int, op, threshold: number }` | Volume vs N-period avg |
| `efficiency_ratio` | `{ period: int, op, threshold: number }` | Kaufman ER |

### 9.2 Patterns

| Primitive | Schema |
|---|---|
| `candle_pattern` | `{ kind: bullish_reversal\|bearish_reversal\|inside_bar\|breakout_candle }` |
| `clv_threshold` | `{ op, threshold: number }` (close-location-value) |
| `body_pct` | `{ op, threshold: number }` (body as % of full range) |

### 9.3 Regime gates

| Primitive | Schema |
|---|---|
| `regime_filter` | `{ indicator: adx\|atr\|bb_width, op, threshold }` |
| `adx_rising` | `{}` (current ADX > previous ADX) |
| `time_of_day` | `{ start: HH:MM, end: HH:MM, tz: utc\|exchange }` |
| `bias_aligned` | `{ direction: long\|short\|either, indicator: ema\|trend, params }` |

### 9.4 Comparisons (used in compound rules)

| Primitive | Schema |
|---|---|
| `crosses_above` | `{ a: indicator_ref, b: indicator_ref }` |
| `crosses_below` | `{ a, b }` |
| `near` | `{ a, b, threshold_pct }` |
| `inside_range` | `{ value, low, high }` |

### 9.5 Combinators

```yaml
type: ALL_OF
rules: [...]              # all must be true

type: ANY_OF
rules: [...]              # at least one must be true

type: N_OF_M
n: 2
rules: [...]              # at least N must be true

type: NOT
rule: {...}               # negate
```

### 9.6 Escape hatch (advanced)

```yaml
type: custom_signal
service: java                 # or http
class: id.co.blackheart.signals.MyCustomSignal   # for java
endpoint: http://localhost:8000/predict           # for http
inputs:                      # data passed to the custom service
  - rsi_14
  - ema_50
  - close
expected_output: boolean
```

Reserved for ML models or microstructure signals that don't fit the standard rule library. Used sparingly — every `custom_signal` reduces the elegance of "config only."

---

## 10. Sizing library

| Method | Spec params | When to use |
|---|---|---|
| `fixed_notional` | `{ usdt: number }` | Simple testing; flat sizing |
| `fixed_quantity` | `{ qty: number }` | Specific quantity targeting |
| `risk_based` | `{ risk_pct, stop_distance_atr }` | Default for most strategies — risk N% of book at stop distance |
| `allocation_based` | `{ pct_of_book }` | Capital allocation per strategy |
| `volatility_targeted` | `{ target_vol_pct, atr_period, lookback_days }` | Vol-targeting; size inversely to recent volatility |

All methods return `notional_usdt` (LONG) or `position_size_btc` (SHORT) per the existing `LiveTradingDecisionExecutorService` contract — engine respects the live-sizing field convention from `~/.claude/projects/.../memory/blackheart_live_trade_sizing.md`.

Methods cap output at `risk.max_position_size_pct × book_value`.

Deferred to Phase 8 (portfolio layer):
- `kelly_fraction` (needs running stats)
- `risk_parity` (needs correlation matrix)
- `pyramid` / `scale_in` (needs multi-leg lifecycle work)

---

## 11. Exit / Position structures

| Structure | Description | Existing? |
|---|---|---|
| `SINGLE` | One entry, one exit (SL or TP, whichever first) | ✅ |
| `TP1_RUNNER` | Partial close at TP1, rest trails | ✅ |
| `TP1_TP2_RUNNER` | Two partials, then trail | ✅ |
| `RUNNER_ONLY` | No fixed TP; trail from entry | ✅ |
| `TRAILING_ONLY` | Same as RUNNER_ONLY but explicit naming | New (alias) |
| `TIME_BASED_EXIT` | Force-close after N bars | New |
| `CONDITIONAL_EXIT` | Exit when condition true | New |

Trailing types (`exit.trail`):
- `chandelier_atr` — `{ multiplier, period }`
- `parabolic_sar` — `{ accel_start, accel_max }`
- `percent_trail` — `{ pct }`
- `breakeven_at_r` — `{ trigger_r }` (move stop to entry at +1R)

`early_exit` block accepts a list of conditions evaluated each bar:
```yaml
early_exit:
  - { type: time_limit, max_bars: 100 }
  - { type: condition, rule: {...}, when: always|profitable|losing }
  - { type: regime_change, from: trending, to: choppy }
```

---

## 12. Validation

### 12.1 Write-time validation

`StrategyDefinitionController` runs validation **before** persisting:

1. **Top-level schema**: code matches `[A-Z0-9_]+`, archetype is registered, version known
2. **Archetype-specific schema**: required entry slots present (per archetype JSON Schema)
3. **Type checks**: every field's value matches its declared type
4. **Range checks**: `risk_pct ∈ (0, 10]`, `period ∈ [1, 500]`, etc.
5. **Cross-field checks**: e.g. `tp1_r < tp2_r`; `bias_interval > interval`
6. **Reference checks**: `symbol` is plumbed in `feature_store`; `interval` is in `[1m, 5m, 15m, 1h, 4h, 1d]`

Validation rejects with structured error: `{ field, code, message, value }` array.

### 12.2 Runtime validation

Engine assumes specs are valid at evaluation time (write-time validator caught issues). However:

- Per-rule try/catch wraps every primitive — a bug in `RuleLibrary.rsi_extreme` cannot crash evaluation
- Numeric guards: NaN/Inf in feature values → rule returns false, not exception
- Missing feature columns → log + return false

Errors recorded in `EngineMetrics.recordError(strategy_code, error)`. Kill-switch trips at threshold.

---

## 13. Spec versioning

### 13.1 Why versioning

Archetypes evolve: a new field is added, a primitive is renamed, a default changes. Without versioning, an engine deploy would silently change behavior of every existing spec on that archetype — a parity disaster.

### 13.2 The version contract

Every spec has `archetype_version`. Engine checks version on load:

| Version compat | Action |
|---|---|
| spec.version == engine.supported_version | Run as-is |
| spec.version < engine.supported_version | Run via `SpecVersionAdapter.migrate(spec)` if migration registered; else refuse + alert |
| spec.version > engine.supported_version | Refuse + alert (engine too old for spec) |

### 13.3 Migration framework

`SpecVersionAdapter` registry:
```java
adapter.register("mean_reversion_oscillator", 1, 2, oldSpec -> {
    // transform v1 spec to v2 shape
});
```

Migrations are idempotent + tested. The original v1 spec is never mutated; migration produces a v2 view for runtime.

### 13.4 Operator workflow when archetype evolves

1. Engineer ships engine with new archetype version (e.g. v3)
2. Engineer ships SpecVersionAdapter migration v2 → v3
3. Operator continues running v2 specs; engine migrates them on the fly
4. Operator may optionally upgrade specs in-place via `POST /api/v1/strategy-definitions/{code}/upgrade-version`

---

## 14. Observability

### 14.1 Spec trace

Every evaluation produces a trace:

```json
{
  "strategy_code": "BBR_v2",
  "account_strategy_id": "uuid",
  "bar_time": "2026-04-29T10:00:00Z",
  "spec_version": 2,
  "phase": "ENTRY_LONG",
  "rules": [
    { "index": 0, "type": "bollinger_extreme", "result": true,  "value": 28415.50 },
    { "index": 1, "type": "rsi_extreme",       "result": true,  "value": 22.4 },
    { "index": 2, "type": "candle_pattern",    "result": false, "value": "no_bullish_reversal" }
  ],
  "decision": "NO_TRADE",
  "decision_reason": "rule[2] failed",
  "eval_latency_us": 145
}
```

Live: sampled at 1% (avoid log volume). Backtest: full trace persisted alongside `BacktestRun` for replay.

### 14.2 Frontend trace UI

A `SpecTraceViewer` component displays the trace as a decision tree:
- Each rule shows its inputs + result
- Failed rules highlighted; passing rules muted
- Decision path obvious at a glance

Replaces "stepping through Java code" as the debugging mechanism.

### 14.3 Metrics

| Metric | Cardinality | Use |
|---|---|---|
| `engine.eval.latency_us` | per strategy_code | Performance regression detection |
| `engine.eval.error_count` | per strategy_code, error_type | Kill-switch trigger |
| `engine.spec.version_skew` | per archetype, version_pair | Migration alerting |
| `engine.spec.cache.size` | global | Memory monitoring |
| `engine.spec.reload.count` | per strategy_code | Hot-reload activity |

Exported via Spring Boot Actuator + Prometheus (Phase 7).

---

## 15. API surface

### 15.1 Strategy definitions (the spec store)

| Method | Path | Body | Behavior |
|---|---|---|---|
| `POST` | `/api/v1/strategy-definitions` | full spec | Create new strategy code (validates archetype + schema) |
| `GET` | `/api/v1/strategy-definitions` | — | List all, with filters (archetype, lifecycle.state) |
| `GET` | `/api/v1/strategy-definitions/{code}` | — | Fetch by code |
| `PUT` | `/api/v1/strategy-definitions/{code}` | full spec | Replace; bumps spec version; triggers cache reload |
| `PATCH` | `/api/v1/strategy-definitions/{code}` | partial | Merge; validates merged result |
| `DELETE` | `/api/v1/strategy-definitions/{code}` | — | Soft-delete (sets `is_deleted=true`); refuses if any LIVE account_strategy uses it |
| `POST` | `/api/v1/strategy-definitions/{code}/upgrade-version` | `{toVersion}` | Migrate spec to a newer archetype version |

### 15.2 Strategy params (per-account overrides)

| Method | Path | Body | Behavior |
|---|---|---|---|
| `GET` | `/api/v1/strategy-params/{accountStrategyId}` | — | Returns `{ overrides, effective }` |
| `GET` | `/api/v1/strategy-params/{accountStrategyId}/defaults` | — | Just archetype defaults from spec_jsonb |
| `PUT` | `/api/v1/strategy-params/{accountStrategyId}` | overrides | Replace all overrides |
| `PATCH` | `/api/v1/strategy-params/{accountStrategyId}` | partial | Merge into existing |
| `DELETE` | `/api/v1/strategy-params/{accountStrategyId}` | — | Reset to archetype defaults |

### 15.3 Archetypes (read-only)

| Method | Path | Body | Behavior |
|---|---|---|---|
| `GET` | `/api/v1/strategy-archetypes` | — | List all archetypes + versions |
| `GET` | `/api/v1/strategy-archetypes/{name}/schema` | — | JSON Schema for archetype's entry fields |
| `GET` | `/api/v1/strategy-archetypes/{name}/schema?version=N` | — | Schema for specific version |
| `GET` | `/api/v1/strategy-archetypes/{name}/example` | — | Reference example spec |

### 15.4 Spec trace (debugging)

| Method | Path | Body | Behavior |
|---|---|---|---|
| `GET` | `/api/v1/spec-traces/backtest/{runId}` | — | Full backtest trace for run |
| `GET` | `/api/v1/spec-traces/live/{accountStrategyId}?from=&to=` | — | Sampled live traces in window |
| `POST` | `/api/v1/spec-traces/replay` | `{spec, features}` | Test eval without committing |

---

## 16. Database schema

### 16.1 strategy_definition (modified)

```sql
ALTER TABLE strategy_definition
    ADD COLUMN archetype VARCHAR(64) NOT NULL DEFAULT 'LEGACY_JAVA',
    ADD COLUMN archetype_version INT NOT NULL DEFAULT 1,
    ADD COLUMN spec_jsonb JSONB,
    ADD COLUMN spec_schema_version INT NOT NULL DEFAULT 1,
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at TIMESTAMP;

CREATE INDEX idx_strategy_definition_archetype
    ON strategy_definition(archetype) WHERE is_deleted = FALSE;
```

For LSR/VCB/VBO existing rows: `archetype = 'LEGACY_JAVA'`, `spec_jsonb = NULL`. Engine skips `LEGACY_JAVA` rows (they route through Java executors).

### 16.2 strategy_param (NEW unified table)

```sql
CREATE TABLE strategy_param (
    strategy_param_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_strategy_id UUID NOT NULL UNIQUE
        REFERENCES account_strategy(account_strategy_id) ON DELETE CASCADE,
    param_overrides     JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             INT NOT NULL DEFAULT 0,
    created_time        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_time        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_strategy_param_account_strategy
    ON strategy_param(account_strategy_id);
```

### 16.3 strategy_definition_history (NEW — for spec evolution audit)

```sql
CREATE TABLE strategy_definition_history (
    history_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_code         VARCHAR(64) NOT NULL,
    archetype             VARCHAR(64) NOT NULL,
    archetype_version     INT NOT NULL,
    spec_jsonb            JSONB NOT NULL,
    operation             VARCHAR(16) NOT NULL,  -- INSERT | UPDATE | DELETE | UPGRADE
    changed_by_user_id    UUID REFERENCES "user"(user_id),
    changed_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    change_reason         TEXT
);

CREATE INDEX idx_strategy_definition_history_code_time
    ON strategy_definition_history(strategy_code, changed_at DESC);
```

Every spec mutation writes a row here. Forensic replay always possible.

### 16.4 spec_trace (NEW — for backtest trace persistence)

```sql
CREATE TABLE spec_trace (
    trace_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    backtest_run_id     UUID REFERENCES backtest_run(backtest_run_id),
    account_strategy_id UUID,                              -- nullable; live trace
    strategy_code       VARCHAR(64) NOT NULL,
    bar_time            TIMESTAMP NOT NULL,
    phase               VARCHAR(32) NOT NULL,              -- ENTRY_LONG, EXIT_LONG, etc.
    spec_snapshot       JSONB NOT NULL,                    -- spec at evaluation time
    rules               JSONB NOT NULL,                    -- per-rule trace array
    decision            VARCHAR(64) NOT NULL,
    decision_reason     TEXT,
    eval_latency_us     INT,
    created_time        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_spec_trace_backtest_run ON spec_trace(backtest_run_id);
CREATE INDEX idx_spec_trace_account_strategy_time ON spec_trace(account_strategy_id, bar_time DESC);
```

Backtest fills this densely; live samples 1%. Operator-controllable retention policy.

### 16.5 Migration sequence

Applied in M1 (foundation):

```
V16__create_strategy_param.sql                  -- new param table (spec-driven only)
V17__add_spec_columns_strategy_definition.sql   -- archetype + spec_jsonb columns
V18__create_strategy_definition_history.sql    -- spec mutation audit log
V19__create_spec_trace.sql                      -- engine decision trace
```

**Design decision (M1, 2026-04-29)**: legacy param tables (`lsr_strategy_param`, `vcb_strategy_param`, `vbo_strategy_param`) are NOT migrated and NOT dropped. They stay as system-of-record for their respective legacy Java strategies forever. The unified `strategy_param` table serves only spec-driven strategies. This eliminates cutover risk on profitable live strategies. Trade-off accepted: a future spec-driven re-implementation of an existing strategy would not share param history with the original (which is correct — they're different strategies).

---

## 17. Phased build plan

Each milestone is **independently shippable**. The system stays working at the end of every milestone. You can stop after any milestone if priorities change.

### M1 — Foundation (1 week)

**Goal**: storage + entity foundation for spec-driven strategies. Legacy strategies (LSR, VCB, VBO) untouched. No engine yet.

**Deliverables**:
- Flyway migrations V16-V19: `strategy_param`, `strategy_definition` spec columns, history, spec_trace
- `StrategyParam` entity + repository (unified, for spec-driven strategies only)
- `StrategyDefinition` entity extended with archetype + spec columns
- `StrategyDefinitionHistory` entity + repository
- `SpecTrace` entity + repository
- `StrategyParamService` (raw map operations; no DTO knowledge)
- `StrategyParamController` (REST surface for spec-driven param overrides)
- Archetype JSON Schemas drafted in `research/templates/`
- Spec validator scaffolding (validates against JSON Schema; no engine yet)

**DoD**:
- All existing LSR/VCB/VBO behavior unchanged in backtest + live (zero code changes to those services)
- Unified controller passes contract tests
- Legacy `lsr_strategy_param`/`vcb_strategy_param`/`vbo_strategy_param` tables continue to function as before
- Rollback migration tested
- `blackheart/CLAUDE.md` updated with new schema

### M2 — Engine v1 (2 weeks)

**Goal**: One archetype works end-to-end through the engine.

**Deliverables**:
- `StrategyEngine` class (initial ~250 LOC)
- `SpecResolver` with cache (no hot reload yet — restart-required initially)
- `RuleLibrary` for `mean_reversion_oscillator` archetype only
- `SizingLibrary` with `fixed_notional`, `risk_based`, `allocation_based`
- `ExitManager` with SINGLE structure
- `SpecTraceLogger` — full trace in backtest, off in live
- `EngineMetrics` skeleton
- `StrategyExecutorFactory` modified to route engine
- Parity test: engine + BBR spec produces byte-identical backtest result vs hand-coded BBR Java

**DoD**:
- Robustness contract invariants 1-6 met (1: no crash, 2: isolation, 3: live/backtest parity, 4: safety perimeter respected, 5: write-time validation, 6: traceability)
- Fuzz test: 1000 randomly-generated specs evaluated without JVM crash
- BBR-as-spec backtest matches BBR-Java backtest within 0 ticks
- Operator can author a `mean_reversion_oscillator` spec via curl, run a backtest, see results

### M3 — Coverage (2 weeks)

**Goal**: All 4 archetypes; sizing complete; exit structures complete.

**Deliverables**:
- `RuleLibrary` extended for `trend_pullback`, `donchian_breakout`, `momentum_mean_reversion`
- `SizingLibrary` adds `volatility_targeted` (5 of 5 common methods complete)
- `ExitManager` adds TP1_RUNNER, TP1_TP2_RUNNER, RUNNER_ONLY, TIME_BASED_EXIT, CONDITIONAL_EXIT
- Trail types: chandelier_atr, percent_trail, breakeven_at_r
- `early_exit` block evaluation
- Per-archetype reference specs in `research/specs/examples/`
- Parity tests for each archetype vs hand-coded equivalents (where they exist)

**DoD**:
- All 4 archetypes pass parity tests
- ~80% of typical algorithmic crypto strategies expressible without escape hatch
- Reference specs run successfully in backtest + paper trade

### M4 — Operations (1 week)

**Goal**: Production-grade operations.

**Deliverables**:
- Hot reload via Postgres LISTEN/NOTIFY
- `SpecVersionAdapter` framework + migration registration
- Spec error rate kill-switch (invariant 9)
- Engine metrics published via Actuator/Prometheus (placeholder, full Phase 7)
- Spec trace persistence in `spec_trace` table
- Operator runbook (`research/PHASE_11_OPERATIONS.md`) for: authoring, debugging, rollback, version migration

**DoD**:
- Robustness contract invariants 7-10 met
- Spec change visible in next bar's evaluation without restart
- Spec error → kill-switch trip → operator alert demonstrated
- Old-version specs continue running while new versions deploy

### M5 — Frontend (1.5 weeks)

**Goal**: Operator authors strategies via UI.

**Deliverables**:
- Archetype schema endpoint client (`front-end/src/lib/api/archetypes.ts`)
- `DynamicStrategyForm` component (renders param fields from schema)
- `SpecEditor` component (advanced view; raw spec edit with syntax validation)
- `SpecTraceViewer` component (renders engine trace as decision tree)
- Integration into existing backtest wizard (replaces hardcoded forms)
- Replace `LsrParamForm`, `VcbParamForm`, `VboParamForm` with unified `StrategyParamForm` reading from archetype schema

**DoD**:
- Operator authors a new spec in UI, runs backtest, promotes to PAPER_TRADE entirely without IDE
- Existing LSR/VCB/VBO param pages continue working (their archetype is `LEGACY_JAVA` → fall back to hand-coded forms; or migrate to schema-driven if their params can be expressed)
- Spec trace viewer shows decision tree for any backtest run

### Total effort

| Mode | Total |
|---|---|
| Focused (full-time) | 7-8 weeks |
| Part-time (~2-3 hrs/day) | 14-18 weeks |

Each milestone is a 1-2 week chunk. Stop points are clean; nothing leaves the system half-broken.

---

## 18. Acceptance & rollout

### 18.1 Per-milestone acceptance

Every milestone has explicit DoD above. Code review checklist per milestone:

- [ ] All robustness contract invariants relevant to milestone met
- [ ] Parity tests pass (where applicable)
- [ ] Fuzz tests pass (M2 onwards)
- [ ] CLAUDE.md updated to reflect new state
- [ ] No regression in existing LSR/VCB/VBO behavior (backtest + live)
- [ ] Migration tested forward + rollback
- [ ] Operator runbook updated

### 18.2 Production rollout (post-M4)

The first production strategy on the engine:

1. **Author spec** with `lifecycle.state = RESEARCH`
2. **Backtest** — must clear quant-grade gates (n≥100, PF CI excludes 1, PSR≥0.95, +20bps slippage positive, walk-forward ROBUST)
3. **Promote to PAPER_TRADE** via `/strategy-promotion/{id}/promote`
4. **Run paper for ≥2 weeks** — operator monitors decisions match expectations
5. **Promote to PROMOTED** — small capital allocation initially (0.1-0.5% of book)
6. **Scale up** capital allocation gradually as confidence builds

This is the same pipeline existing strategies follow; the engine just makes step 1 dramatically cheaper.

### 18.3 Rollback plan

If the engine misbehaves in production:

1. **Per-strategy**: trip kill switch via `POST /api/v1/account-strategies/{id}/kill-switch` — immediate halt
2. **Spec-level**: revert to previous spec via history table (`POST /strategy-definitions/{code}/revert?to_history_id=X`)
3. **Engine-level**: feature flag in `application.properties` (`engine.enabled=false`) disables routing through engine — all engine-driven strategies become inert; existing Java strategies unaffected
4. **Total rollback**: restore from pre-cutover backup of `strategy_param` legacy tables

The legacy tables stay as backup for ≥30 days post-M5 cutover.

---

## 19. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| DSL expressiveness gap (researcher wants something engine can't express) | Medium | Medium | Hybrid model permanent; Java escape hatch always available; `custom_signal` primitive for ML/microstructure |
| Spec evolution breaks production | Low | High | Spec versioning + migration framework + history table |
| Live/backtest parity drift | Low | Critical | Single engine class; CI parity tests every commit; spec snapshot stored with backtest run |
| Performance regression in live path | Low | Medium | Eval latency target <1ms per bar; spec resolution cached; Prometheus alerting on regression |
| Operator authors buggy spec | Medium | Low | Write-time validation; runtime sandbox; kill-switch on error rate |
| Hot reload corrupts in-flight evaluation | Low | High | Atomic cache swap; spec snapshot at evaluation start; integration tests cover concurrent reload + eval |
| Spec store grows unbounded | Low | Low | History table partitioned by month (Phase 12); soft-delete gated by no-LIVE-binding |
| Engine codebase becomes too complex | Medium | Medium | Clear primitive contracts; per-archetype isolation; ban from "strategy logic" leaking outside RuleLibrary |
| Frontend dynamic form UX inferior to hand-coded | Low | Low | Archetype schemas can declare UI hints (slider vs input, ranges, units); fall back to advanced spec editor |

---

## 20. Future extensions (post-M5)

These extend the engine's power; not required for "fully modular trading service":

| Phase | Feature | Effort |
|---|---|---|
| P12 | LIMIT / STOP_MARKET order types | 5-7 days |
| P13 | Cross-asset rules (after Phase 3 ETH plumbing) | 1 week |
| P14 | Custom signal hooks (HTTP + Java SPI) | 1 week |
| P15 | Population-based search over specs (after engine matures) | 3-4 weeks |
| P16 | Spec marketplace / sharing format (export/import portable specs) | 1 week |
| P17 | Multi-leg trade lifecycle (pyramid, scale-in) | 2-3 weeks |

---

## 21. Open questions (to resolve before M1 starts)

These are decisions to make explicitly with the operator:

1. **Where does `strategy_definition.spec_jsonb` live for legacy strategies (LSR/VCB/VBO)?** Either NULL with `archetype='LEGACY_JAVA'`, OR a synthesized spec for documentation/replay purposes (engine never reads it).
   - Recommendation: NULL with `archetype='LEGACY_JAVA'`. Synthesizing specs invites parity confusion.

2. **Cache invalidation on spec change — strict consistency or eventual?**
   - Strict (NOTIFY/LISTEN, atomic swap): more code, instant effect.
   - Eventual (poll every 30s): simpler, up to 30s lag.
   - Recommendation: strict. Hot reload is a stated robustness invariant.

3. **Spec version numbering — global or per-archetype?**
   - Global: simpler reasoning, harder to evolve archetypes independently.
   - Per-archetype: more complex matrix, more flexibility.
   - Recommendation: per-archetype. Each archetype has its own evolution.

4. **Dynamic frontend form — JSON Schema rendering or hand-built per archetype?**
   - JSON Schema: zero per-archetype work, generic.
   - Hand-built: better UX, more code.
   - Recommendation: JSON Schema with UI-hint extensions (ranges, units, group labels). 80% of UX with 10% of effort.

5. **Spec error rate kill-switch threshold — fixed or per-strategy configurable?**
   - Fixed: simpler.
   - Configurable: flexible but more cognitive load.
   - Recommendation: fixed default (`5 errors / 100 bars`), per-strategy override available via spec field.

---

## 22. References

- `blackheart/CLAUDE.md` — current architecture, working rules, domain invariants
- `blackheart/research/CLAUDE.md` — research workflow + autonomous iteration
- `blackheart/research/PHASE_2_SPECS.md` — current YAML spec language (codegen approach)
- `blackheart/research/DEPLOYMENT.md` — production deployment runbook
- `front-end/CLAUDE.md` — frontend architecture
- `front-end/API_CONTRACT.md` — API contract reference
- `~/.claude/projects/.../memory/` — operator memory (preferences, lessons, status)

---

## 23. Document conventions

- This blueprint is read by the agent on every related task; keep it accurate.
- When implementation diverges from blueprint, **update the blueprint immediately** in the same PR.
- Section numbers are stable; new sections append (don't renumber).
- Code references use `path:lineno` format for grep-ability.
- Effort estimates are operator-time, not calendar-time.

---

**Last updated**: 2026-04-29
**Status**: Pre-implementation. M1 not yet started.
**Authoring**: This document was authored by the agent based on collaborative architecture sessions with the operator. Operator has final say on every design decision; agent implements once direction is locked.
