# RESEARCH PLAN 2026-05-07i
## DCB2 BTCUSDT 1h — Donchian Breakout on BTC native 1h

**hypothesis_id:** 8cf7f89c-137d-4bbe-8ffa-b234d933f950
**strategy_code:** DCB2
**interval:** 1h
**instrument:** BTCUSDT
**session_date:** 2026-05-07
**plan_version:** i (9th plan this session)

---

## Background

DCB2 (DonchianBreakoutEngine, archetype=donchian_breakout) has demonstrated real edge in prior sessions:
- ETHUSDT 1h: PF=1.13, n=294 at adxEntryMin=26 (failed +20bps gate: net return only 0.7% total)
- ETHUSDT 4h: CI_lower=1.029 at best cell (blocked by VBO Spearman corr=0.70)
- ETHUSDT 15m: NO_EDGE (PF=0.72)

All prior DCB2 tests were on ETHUSDT. BTCUSDT 1h has never been tested.

## Hypothesis

DCB2 BTC 1h may have different characteristics than ETH:
1. VBO correlation: VBO (production) runs on BTC 15m. DCB2 BTC 1h uses 60-min bars.
   The 4x timeframe difference means DCB2 1h and VBO 15m entries will not temporally align
   as closely as DCB2 ETH 4h and VBO 15m. Expected correlation: <0.50 (vs ETH 4h corr=0.70).
2. BTC breakout quality: BTC has larger absolute moves but the Donchian breakout mechanism
   (N-bar high/low + ADX gate) may capture cleaner trend initiation on BTC 1h.
3. Edge magnitude: ETH 1h PF=1.13 at adxEntryMin=26. If BTC 1h gives PF>1.2 with n>=100,
   and VBO corr<0.50, the portfolio-adjusted CI_lower may exceed 1.0.

Mechanism: Price breaks Donchian N-bar upper band (long) or lower band (short), ADX above
adxEntryMin confirming trend strength, enter on close, stop at stopAtrMult*ATR from entry,
take profit at tpR * risk. Pure breakout mechanics with no cross-timeframe bias filter.

## Axes and Null-Screen Design

Three dimensions:

| Axis | Range | Rationale |
|------|-------|-----------|
| adxEntryMin | [20, 35] | Full range around ETH 1h proven zone at adxEntryMin=26 |
| stopAtrMult | [1.0, 2.5] | Stop placement: tight (1.0x) to wide (2.5x ATR) |
| tpR | [1.5, 3.0] | Reward target: conservative to aggressive |

null-screen: 8 draws, seed=42, LHS sampling

Falsification conditions:
- If P95(PF)<1.2 AND share(PF>=1.2)<0.1 → NO_EDGE, archive DCB2 BTC
- If median n_trades < 30 → frequency too low, try DCB2 BTC 4h instead

## Sweep Plan (if null-screen EDGE_PRESENT with adequate n)

27-cell grid: adxEntryMin[22,26,30] x stopAtrMult[1.2,1.6,2.0] x tpR[1.5,2.0,2.5]
Rationale: centered on ETH proven zone (adxEntryMin=26), 3D traversal.

V11 gates required for SIGNIFICANT_EDGE:
- n_trades >= 100
- PF 95% CI lower > 1.0
- +20bps slippage net positive
- PSR >= 0.95 (DSR >= 0.95 with multi-testing scaling)
- Portfolio gate: effective_pf_lo = CI_lower * (1 - 0.5*vbo_corr) > 1.0

## Portfolio Correlation Gate

Key risk: if VBO correlation >= 0.70 (same as ETH 4h), need CI_lower > 1.538 to pass.
At corr=0.50, need CI_lower > 1.333.
At corr=0.30, need CI_lower > 1.176.
At corr=0.10, need CI_lower > 1.053.

The ETH 1h precedent (PF=1.13) suggests CI_lower would be around 1.02-1.08 at n=294.
To pass portfolio gate at corr=0.50: need PF~1.30+ with n>=200.

## Risk Assessment

- DCB2 BTCUSDT 1h account_strategy created: 22891695-ba49-42d4-bce0-89a8bb42c965
  (enabled=false, simulated=true, priorityOrder=99)
- DCB2 strategy_definition: 78939918 (archetype=donchian_breakout)
- No production impact: enabled=false throughout research
- VBO production untouched: BTC 15m with different mechanism

## Decision Tree

```
null-screen result:
  EDGE_PRESENT AND median_n >= 30 → queue 27-cell grid sweep
  EDGE_PRESENT BUT median_n < 30  → inadequate frequency; try DCB2 BTC 4h
  NO_EDGE_DETECTED                 → archive DCB2 BTC; pivot to MMR ETHUSDT or BBR ETH 4h
```
