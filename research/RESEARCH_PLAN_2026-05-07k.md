# RESEARCH_PLAN_2026-05-07k — MMR ETHUSDT 1h: Extended maxBarsHeld

## Hypothesis
**hypothesis_id**: `958735fb-465d-4e1e-9957-4d8ed52856fb`  
**pre-registered**: 2026-05-07T07:12:39 (journal_id `958735fb`)

## Background & Motivation

Prior null-screen on MMR ETHUSDT 1h (hypothesis `5557851f`, journal `8e88edc1`) returned NO_EDGE_DETECTED:
- All 8 LHS draws: PF = 0.574 – 0.696 (mean=0.642, P95=0.696)
- Trade frequency: adequate (n=190–338)
- Axes tested: extremeAtrMult[1.5–3.0] x rsiOversoldMax[22–38] x stopAtrBuffer[0.6–1.8]

**Root cause diagnosis**: The default `maxBarsHeld=12` force-exits positions after 12h on 1h bars. ETH mean-reversion from an EMA200-extreme overshoot back to the EMA50 target typically takes 24–96 bars (1–4 days) at the 1h timeframe. All draws in the prior screen shared this structural constraint — the strategy was systematically booking losses before reversions completed.

## Mechanism

MomentumMeanReversionEngine LONG entry conditions:
- `close < EMA200 - extremeAtrMult * ATR` (extreme downside displacement)
- `RSI < rsiOversoldMax` (momentum extreme confirmed)
- `targetEMA (EMA50) > close` (target is above current price)
- Take-profit: close >= EMA50
- Stop-loss: close <= entry - stopAtrBuffer * ATR

The mechanism is **sound** — extreme displacement from EMA200 combined with RSI oversold is a legitimate mean-reversion signal. The prior failure was NOT about signal quality but about the maximum holding period being too short for the reversion to complete. Extending `maxBarsHeld` to 48–96 bars gives the trade room to reach EMA50.

## Strategy Differentiation from LSR/VCB/VBO

- LSR: trend-following momentum longs on 15m
- VCB: volatility-breakout on 1h
- VBO: Donchian-breakout with volume filter
- MMR: **mean-reversion** from extreme displacement — mechanistically OPPOSITE to VBO and VCB (expected negative or near-zero Spearman correlation with breakout strategies)

## Null-Screen Design

**Engine**: MomentumMeanReversionEngine (strategy_code: `MMR`)  
**Instrument**: ETHUSDT  
**Interval**: 1h  
**n_draws**: 8  
**seed**: 43 (different from prior seed=42 to avoid inadvertent re-discovery)

**Param ranges** (3 axes — meets ≥3 dimension requirement):
| Parameter | Low | High | Rationale |
|---|---|---|---|
| extremeAtrMult | 1.5 | 3.0 | Distance from EMA200 required; wider = fewer but more extreme entries |
| stopAtrBuffer | 0.6 | 1.8 | Stop distance; must not be too tight given extended hold |
| maxBarsHeld | 24 | 96 | Core fix: 24h–96h max hold to allow reversion completion |

**Note**: rsiOversoldMax is intentionally removed from this sweep. Prior result showed rsiOversoldMax ranging 22–38 had no material effect on PF (all draws negative). Replacing it with maxBarsHeld as the structural fix axis. The default rsiOversoldMax=30 will apply.

## Decision Rules

| Null-screen verdict | Action |
|---|---|
| EDGE_PRESENT (P75>=1.2, share>=0.25) | Proceed to full sweep (3D grid, budget=18) |
| NO_EDGE_DETECTED | Pivot to MMR ETH 4h (longer bars = more natural hold time) |
| DEGENERATE (n<7 in multiple draws) | Journal and pivot — frequency starvation |

## Portfolio Safety

MMR mean-reversion is mechanistically opposite to VCB/VBO breakout strategies. This is not a correlated duplicate. LSR is momentum-based on short timeframes — different regime.

## Success Criteria (V11 Gates)

For graduation candidate:
- n >= 100 trades
- PF 95% CI lower > 1.0
- PSR >= 0.95 (DSR >= 0.95 with cumulative-trial scaling)
- +20bps slippage still positive
- Walk-forward: stability_verdict = ROBUST
- Annualized return >= 10% net of fees + 20bps slippage
