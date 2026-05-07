# RESEARCH PLAN 2026-05-07j
## MMR ETHUSDT 1h — Momentum Mean-Reversion Fade on ETH

**hypothesis_id:** 5557851f-5960-47af-9067-42121187453e
**strategy_code:** MMR
**interval:** 1h
**instrument:** ETHUSDT
**session_date:** 2026-05-07
**plan_version:** j (10th plan this session)

---

## Background

MMR (MomentumMeanReversionEngine, archetype=momentum_mean_reversion) has been tested only
on BTCUSDT 1h, returning NEGATIVE (PF=0.53). ETHUSDT 1h is completely untested for MMR.

Prior null-screens this session:
- TPB BTC 1h: DEGENERATE_EDGE (frequency starvation)
- TPB BTC 4h: NO_EDGE (n=0-3, all losses)
- BBR BTC 1h: NO_EDGE (PF=0.13-0.23)
- DCB2 BTC 1h: NO_EDGE (PF=0.53-0.75)

The common theme: all 4 spec-driven engines are non-viable on BTCUSDT at these params.
MMR ETH is the most mechanistically differentiated remaining path.

## Hypothesis

MMR fades EMA200 extremes. ETH has different mean-reversion dynamics than BTC:
1. Higher beta (ETH/BTC beta ~1.2-1.5): ETH overshoots more during BTC moves
2. More frequent RSI extremes: ETH has more frequent RSI<25 and RSI>75 readings at 1h
3. Faster reversion: when BTC mean-reverts from extreme, ETH tends to revert faster
4. Lower liquidity: ETH market depth is shallower, making extreme moves more likely to overshoot

Mechanism: Close < EMA200 - extremeAtrMult*ATR (long), RSI < rsiOversoldMax, EMA50 > close.
TP = EMA50 (mean-reversion target). Stop = close - stopAtrBuffer*ATR.
Force exit at maxBarsHeld (default 12 candles = 12h on 1h timeframe).

No bias timeframe required — pure momentum extreme fade.

## Portfolio Correlation Analysis

MMR fires when price overshoots EMA200 (the opposite of breakout conditions).
VBO fires when volatility compresses then expands (breakout from Bollinger Band range).
These are structurally opposite: MMR enters during high-volatility extreme; VBO enters
after low-volatility compression. Expected Spearman correlation: near 0 or negative.

If corr ≈ 0: standard gate applies (CI_lower > 1.0 without portfolio penalty).
If corr ≈ -0.20: portfolio-adjusted CI_lower threshold is lower than 1.0 (bonus).

## Axes and Null-Screen Design

Three dimensions:

| Axis | Range | Rationale |
|------|-------|-----------|
| extremeAtrMult | [1.5, 3.0] | Entry sensitivity: how far below EMA200 before entry |
| rsiOversoldMax | [22, 38] | RSI filter strength: strict (22) to loose (38) |
| stopAtrBuffer | [0.6, 1.8] | Stop placement: tight (0.6x ATR) to wide (1.8x ATR) |

null-screen: 8 draws, seed=42, LHS sampling

Falsification:
- If P95(PF)<1.2 AND share(PF>=1.2)<0.1 → NO_EDGE, try MMR ETH 4h
- If all draws n_trades < 10 → frequency issue, try wider extremeAtrMult range

## Sweep Plan (if null-screen EDGE_PRESENT)

27-cell grid: extremeAtrMult[1.5,2.0,2.5] x rsiOversoldMax[25,30,35] x stopAtrBuffer[0.8,1.2,1.6]

V11 gates: n>=100, CI_lower>1.0, +20bps positive, PSR>=0.95
Portfolio gate: CI_lower*(1-0.5*vbo_corr) > 1.0 — at corr=0, standard gate; at corr=-0.2, lower threshold

## Setup

- MMR ETHUSDT 1h account_strategy: 9101c968-cfd0-49ee-a20c-58e53b73861c (enabled=false, simulated=true)
- MMR strategy_definition: f4732c85 (archetype=momentum_mean_reversion)

## Decision Tree

```
null-screen result:
  EDGE_PRESENT → queue 27-cell grid sweep
  NO_EDGE_DETECTED → try MMR ETH 4h OR MeanReversionOscillator (BBR) ETH 4h
```
