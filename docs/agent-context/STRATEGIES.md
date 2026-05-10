# Strategy / engine catalog

> One-line inventory of every strategy and archetype engine in the codebase.
> Loaded on demand — agents grep this first instead of scanning the
> `engine/` and `service/strategy/` packages.

## Conventions

- **Code paths** are clickable — `path:line` so the agent can `Read` directly without a `Glob`.
- **Status values:** `production` (live capital, protected), `research` (paper / backtest only), `discarded` (kept for archival, not wired up).
- **Two generations live side-by-side:**
  - **Legacy hand-written** in `service/strategy/*StrategyService.java` — frozen production code.
  - **Spec-driven archetypes** in `engine/*Engine.java` — Phase 2+ parametric engines configured by `strategy_definition.spec_jsonb`.

## Production (protected — see WORKING_RULES.md "Don't touch")

| Code | Class | Path | One-liner |
|---|---|---|---|
| **LSR** | `LsrStrategyService` | `src/main/java/id/co/blackheart/service/strategy/LsrStrategyService.java` | Long-Short Reversal — fades extreme RSI with EMA200 trend filter. ~+20%/yr live. |
| **VCB** | `VcbStrategyService` | `src/main/java/id/co/blackheart/service/strategy/VcbStrategyService.java` | Volatility Contraction Breakout — enters on Bollinger band squeeze release. ~+20%/yr live. |
| **VBO** | `VolatilityBreakoutStrategyService` | `src/main/java/id/co/blackheart/service/strategy/VolatilityBreakoutStrategyService.java` | Volatility Breakout — ATR-scaled range breakout with ADX trend confirmation. ~+20%/yr live. |

## Research — legacy hand-written

| Code | Class | Path | One-liner |
|---|---|---|---|
| **TPR** | `TrendPullbackStrategyService` | `src/main/java/id/co/blackheart/service/strategy/TrendPullbackStrategyService.java` | Trend pullback to EMA20 inside confirmed trend. V2 sizing: risk-based notional (`riskPerTradePct` × capital, capped by `maxAllocationPct`); falls back to legacy capital-allocation flat sizing when the params are unset. Not yet profitable; see TrendPullbackEngine for the spec-driven successor. |
| **FCARRY** | `FundingCarryStrategyService` | `src/main/java/id/co/blackheart/service/strategy/FundingCarryStrategyService.java` | Funding-rate carry trade. Seeded by Flyway V36 (`LEGACY_JAVA` archetype). |

## Research — spec-driven archetype engines (`engine/`)

| Code / archetype | Class | Path | One-liner |
|---|---|---|---|
| **DCB** | `DonchianBreakoutEngine` | `src/main/java/id/co/blackheart/engine/DonchianBreakoutEngine.java` | Donchian-N breakout with ADX/volume confirmation; break-even shift + timed exit. Generalises the discarded DCT. |
| **MRO** | `MeanReversionOscillatorEngine` | `src/main/java/id/co/blackheart/engine/MeanReversionOscillatorEngine.java` | Outer-band mean reversion with RSI exhaustion gate; exits at EMA20 mid. Generalises the discarded BBR. |
| **MMR** | `MomentumMeanReversionEngine` | `src/main/java/id/co/blackheart/engine/MomentumMeanReversionEngine.java` | EMA-anchored momentum mean reversion (e.g. close >2·ATR below EMA200) back to a target EMA. Anchor + target configurable in spec body. |
| **TPB** | `TrendPullbackEngine` | `src/main/java/id/co/blackheart/engine/TrendPullbackEngine.java` | Trend pullback with candle-quality gates (body ratio, CLV, volume); TP1 break-even shift then ATR runner. V2 risk-based sizing (`riskPerTradePct` / `maxAllocationPct` spec params; legacy fallback retained). Generalises the legacy TPR. |

## Discarded (kept for archival reference)

| Code | Reason | Successor |
|---|---|---|
| **DCT** | Donchian breakout — 10%/yr, no margin after fees+slippage | `DonchianBreakoutEngine` (DCB) |
| **BBR** | Bollinger reversal — `NO_EDGE` verdict | `MeanReversionOscillatorEngine` (MRO) |
| **CMR** | Cross-market reversal — never traded live | — |

## Adding a new strategy

1. **Spec-driven (preferred):** add `engine/<Name>Engine.java` extending the engine base. Use `EngineContextHelpers` for shared veto/risk lookups (see WORKING_RULES.md DRY rules). Wire archetype name into `strategy_definition.archetype` via Flyway seed migration.
2. **Legacy hand-written:** only if the strategy can't be expressed as an archetype. Add `service/strategy/<Name>StrategyService.java`. Update this catalog.
3. **Either way:** add a row to the appropriate table above. Production strategies require an explicit user instruction (per the "Don't touch" hard list).

## Maintenance

This file is updated **in the same PR** as the code change. See WORKING_RULES.md → "Catalog maintenance" for the trigger list. If you add a class to `engine/` or `service/strategy/` without updating this table, the next agent session pays for a wasted Glob.
