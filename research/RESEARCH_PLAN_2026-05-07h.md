# RESEARCH PLAN 2026-05-07h
## TPB BTCUSDT 4h — TrendPullback native 4h timeframe

**hypothesis_id:** 4324fe63-d905-4002-8595-765d35afc5dd
**strategy_code:** TPB
**interval:** 4h
**instrument:** BTCUSDT
**session_date:** 2026-05-07
**plan_version:** h (8th plan this session)

---

## Background

TPB BTCUSDT 1h (hypothesis df907457) returned a nominal EDGE_PRESENT null-screen verdict,
but researcher override classified it as DEGENERATE_EDGE. Root cause: frequency starvation.
The 4h bias filter inside a 1h strategy created a cross-timeframe conflict: the backtest
loads a separate 4h EMA series to check bias, which only satisfied the bias condition on
a minority of 1h bars. Result: 2-15 trades per 2-year period. Only draws with n<=5 showed
PF>1.2 — not credible samples. All draws with n>=7 were losers (PF=0.43-0.60).

## Hypothesis

On BTCUSDT 4h, TrendPullbackEngine (archetype=trend_pullback) removes the cross-timeframe
conflict. The engine calls `spec.bodyString("bias.interval", "4h")` — on 4h, the bias
timeframe equals the trading timeframe. Both `biasFeatureStore` and the entry `featureStore`
draw from the same 4h candle series. The bias gate (EMA50>EMA200 + price>EMA200 + ADX band)
now checks the SAME series the entry gate uses, eliminating the conflict.

Mechanism: EMA50>EMA200 4h trend stack; price pullback to EMA20 within ATR tolerance;
reclaim candle (body ratio>0.45, CLV>0.70, rvol>1.10); composite score>=0.55; ADX band gate.

Expected outcome: 30-80 trades per 2-year period in adxEntryMin=[24-32] range vs 2-15 on 1h.

## Axes and Null-Screen Design

Three dimensions (no overlap with prior DISCARD history on TPB):

| Axis | Range | Rationale |
|------|-------|-----------|
| adxEntryMin | [22, 32] | Tighter range around positive-PF zone observed on 1h (adx=29-30) |
| stopAtrBuffer | [0.45, 0.85] | Full range: tighter stops vs wider accommodating 4h ATR moves |
| tp1R | [1.5, 3.0] | Reward target: 1.5R conservative, 3.0R aggressive runner |

null-screen: 8 draws, seed=42, LHS sampling

Falsification condition: If P95(PF)<1.2 AND share(PF>=1.2)<0.1 across all 8 draws,
OR if median n_trades < 20, archive TPB BTCUSDT 4h and pivot to ETHUSDT TPB.

## Sweep Plan (if null-screen EDGE_PRESENT)

27-cell grid: adxEntryMin[24,28,32] x stopAtrBuffer[0.50,0.65,0.80] x tp1R[1.8,2.2,2.8]
Rationale: 3D traversal covers trend quality x stop placement x reward target.

V11 gates required for SIGNIFICANT_EDGE:
- n_trades >= 100
- PF 95% CI lower > 1.0
- +20bps slippage net positive
- PSR >= 0.95 (DSR >= 0.95 with multi-testing scaling)

## Setup Required

1. CREATE account_strategy: accountId=1817bef5, strategyCode=TPB, symbol=BTCUSDT,
   intervalName=4h, enabled=false, simulated=true
   (The strategy_definition 2da83c7c-ced8-43e4-81a2-5483bdab9540 already exists from
   the 1h setup — TPB shares one strategy_definition for all intervals)

## Risk Assessment

- No correlation risk with LSR/VCB/VBO: TPB fires on EMA pullback reclaim; VBO fires on
  breakout expansion (structurally opposite). Expected max correlation <= 0.30.
- No production impact: enabled=false, simulated=true throughout research.
- Account_strategy creation is a research-JVM API call, not a trading-JVM operation.

## Decision Tree

```
null-screen result:
  EDGE_PRESENT AND median_n >= 20 → queue 27-cell grid sweep
  EDGE_PRESENT BUT median_n < 20  → DEGENERATE_EDGE (same as 1h); pivot to ETHUSDT
  NO_EDGE_DETECTED                 → archive TPB BTC; pivot to ETHUSDT TPB or next archetype
```
