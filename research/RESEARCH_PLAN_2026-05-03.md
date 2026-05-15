# Research Plan — 2026-05-03

**Status:** Prepared by quant-researcher agent after full session exhaustion.
**Goal:** Find 4th profitable strategy >= 10%/yr net after fees+slippage, walk-forward ROBUST.
**Current SIGNIFICANT_EDGE count:** 0

---

## Session Outcome Summary

All 5 active hypotheses from today's session were exhausted without a SIGNIFICANT_EDGE.

| Hypothesis | Strategy | Description | Result |
|---|---|---|---|
| ff204773 | VBO ETH 15m | atrExpansionMin x rvolMin rebalance | FALSIFIED — all INSUFFICIENT_EVIDENCE |
| aa468604 | CMR ETH 1h | adxMaxForChop x rsiOversoldMax x stopAtrBuffer | FALSIFIED — PF 0.08-0.54, bull-regime structural |
| d02e2c86 | FCARRY BTC 1h | entryZ x exitZ x holdMaxBars | FALSIFIED — n=65 PF=0.692 across all cells |
| 35ba7e8d | DCB2 ETH 1h | DonchianBreakoutEngine sweep | BLOCKED — engine bug, produces n=0 |
| 7d3b4c81 | VBO ETH 15m | tp1R rescue sweep | FALSIFIED — best n=149 PF=1.135 CI=[0.775,1.617] |

---

## Key Findings

### 1. Regime dependence is the dominant failure mode

The 2024-2026 dataset is dominated by a persistent BULL trend. All mean-reversion (CMR) and carry-fade (FCARRY) strategies fail structurally because the regime distribution is skewed.

VBO ETH 15m regime breakdown (best cell, tp1R=1.50 minSignalScore=0.60 atrExpansionMin=2.00):
- BEAR: n=44, PnL+5.7, win_rate=50%
- NEUTRAL: n=32, PnL+2.0, win_rate=47%
- BULL: n=45, PnL-3.1, win_rate=33%

The BULL regime alone erases all gains. This is not a parameter problem — it is a structural one. The strategy needs a BEAR-only gate.

### 2. VBO ETH 15m has the most salvageable signal

VBO ETH 15m is the only strategy tested today that shows consistent positive expectancy in non-BULL regimes. With a slope_200 < 0 (bear/neutral) entry gate:
- Estimated usable n: ~76 trades (BEAR+NEUTRAL only)
- Estimated PF: would need verification but historical BEAR+NEUTRAL combined is strongly positive
- Risk: reduced n may fall below n>=100 threshold — needs testing

This is the highest-priority next hypothesis.

### 3. DonchianBreakoutEngine has a critical engine bug

`DonchianBreakoutEngine.java` line: `if (prevClose.compareTo(prev.getDonchianUpper20()) > 0)` is logically impossible because `donchianUpper20 = highestHigh(20 bars ending at prevBar)` which always >= prevBar.high >= prevBar.close. The condition can never be true. The engine produces n=0 for all param configurations.

**Required developer fix:** "shift channel by 1 bar" — the Donchian channel should be computed on bars ending at bar[t-2] (the bar before prev), not bar[t-1] (which includes prevBar's own high). This is a 1-line fix in `ResearchFeatureEnrichmentService` or in the engine's entry condition.

### 4. FCARRY needs more data or a different approach

The funding-rate dataset has 2,560 records (every 8h from 2024-01-01). With entryZ=1.5, only ~65 qualifying bars have extreme z-scores. This is too sparse for stat-sig. Options:
- Lower entryZ to 0.5-1.0 (more signals, but lower signal quality)
- Add open-interest confirmation to lift signal quality while lowering entryZ
- Wait for longer funding-rate history (>2 years)

---

## Next Research Priorities (ordered)

### Priority 1: VBO ETH 15m with slope_200 BEAR gate (IMMEDIATE)

**Hypothesis to pre-register:** VBO ETH 15m with slope_200 < 0 entry filter.

Pre-condition: Need to confirm slope_200 is a computed feature in the feature store. If not, request developer to add it as a simple 200-bar linear regression slope of close prices.

Sweep design (once feature confirmed):
- Axis 1: minSignalScore (0.55, 0.60, 0.65)
- Axis 2: atrExpansionMin (1.80, 2.00, 2.20)
- Fixed: tp1R=1.50, slope_200_gate=enabled
- Budget: 9 cells (3x3)
- Predicted: BEAR+NEUTRAL n~76-90, PF CI likely narrower — may or may not cross 1.0

**BLOCKER:** Verify slope_200 feature availability before enqueuing.

### Priority 2: Fix DonchianBreakoutEngine and test DCB2 (DEVELOPER TASK)

File: `/c/MyFiles/blackheart/blackheart/src/main/java/id/co/blackheart/engine/DonchianBreakoutEngine.java`

Required change: Entry condition should compare prevClose against donchianUpper of the bar BEFORE prevBar (shift channel computation by 1 bar). This requires either:
- Computing donchianUpper20 using bars [t-2, t-21] instead of [t-1, t-20], OR
- Changing the engine to look at `bar[t-2].close > bar[t-2].donchianUpper20 of bars ending at t-3`

Once fixed, re-run hypothesis 35ba7e8d (DCB2 ETH 1h) with the full sweep.

### Priority 3: New strategy archetype — Momentum Pullback on BTC 1h

**Rationale:** The 2024-2026 BULL regime should favor momentum-following. All tested strategies are mean-reverting or regime-neutral. A momentum-with-pullback archetype would have POSITIVE regime exposure in the current data.

**Concept:** Enter long when:
- Price is above 200-bar EMA (trend filter)
- Short-term RSI(5) has pulled back below 30 (oversold dip in uptrend)
- Volume > 20-bar average (confirms dip is real, not exhaustion)

Exit: Either fixed R multiple or trailing stop above ATR.

**Implementation path:** Use `MomentumMeanReversionEngine` if it supports these features, or spec a new `TrendPullbackEngine` sweep. Check `TrendPullbackEngine.java` for available params.

**Required pre-work:**
1. Check TrendPullbackEngine available parameters
2. Pre-register hypothesis
3. Design 3-dim sweep: rsiOversold x atrMultiplier x trendEmaLen

### Priority 4: VCB BTC 5m (shorter timeframe test)

VCB is profitable on BTC 15m. The 5m interval may offer more signal frequency at the cost of more noise. If n can reach 200+ with acceptable PF, the CI width may be achievable.

**Pre-condition:** Confirm VCB is plumbed for 5m interval (it uses relVolBreakoutMin — check if 5m bars have adequate volume data).

### Priority 5: LSR ETH 1h (transfer learning from BTC)

LSR is profitable on BTC. ETH/BTC correlation is ~0.85. LSR signal on ETH 1h may have an independent positive expectancy.

**Constraint:** Do NOT modify LSR Java code or default params. Only test via parameter sweep through orchestrator using ETH as instrument.

---

## Blocked Items (require developer action)

| Blocker | Description | Required Fix |
|---|---|---|
| DonchianBreakoutEngine bug | Entry condition `prevClose > donchianUpper20` is impossible | Shift channel computation 1 bar earlier |
| slope_200 feature | Not confirmed in feature store | Add to ResearchFeatureEnrichmentService |
| FCARRY sparse data | 65 qualifying bars at entryZ=1.5 | Need longer funding-rate history OR lower entryZ with OI filter |

---

## Discarded Strategy Space (do not re-sweep without override + journal)

Per re-discovery gate (V28 Tier 1):

| Strategy | Interval | Instrument | Axis-set discarded | Reason |
|---|---|---|---|---|
| CMR | 1h | ETHUSDT | adxMaxForChop x rsiOversoldMax x stopAtrBuffer | Bull-regime structural failure |
| FCARRY | 1h | BTCUSDT | entryZ x exitZ x holdMaxBars | Insufficient data volume |
| VBO | 15m | ETHUSDT | atrExpansionMin x rvolMin | Bull-regime bleed (without regime gate) |
| VBO | 15m | ETHUSDT | tp1R x minSignalScore x atrExpansionMin | Same — needs slope_200 gate |

---

## Infrastructure Notes

- Research JVM rebuilt 2026-05-03 12:46 — current jar is active
- Orchestrator (FastAPI 8082) running, all endpoints healthy
- Trading JVM (8080) — DO NOT RESTART
- DB at V40; no schema changes needed for planned sweeps
- All sweeps must pre-register hypothesis in journal BEFORE enqueuing

---

*Written by quant-researcher agent — 2026-05-03*
*Journal references: ff204773, aa468604, d02e2c86, 35ba7e8d, 7d3b4c81, f827740a, d0a2af8e*
