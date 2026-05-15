# RESEARCH_PLAN_2026-05-07l — MMR ETHUSDT 4h: Natural Hold Time Hypothesis

## Hypothesis
**hypothesis_id**: `aff00b73-f72a-48ab-a7a1-f7453e8664de`  
**pre-registered**: 2026-05-07T07:20:38 (journal_id `aff00b73`)

## Background & Motivation

**Why 4h instead of 1h?**

MMR ETH 1h (hypothesis `5557851f`) showed NO_EDGE with default maxBarsHeld=12 (12h max hold). The structural diagnosis: ETH mean-reversion from EMA200-extreme to EMA50 target takes 24-96 hours, but the engine force-exits at 12h.

MMR ETH 1h extended-maxBarsHeld (hypothesis `958735fb`) tests extending the hold to 24-96 bars on the 1h timeframe. This plan is the parallel/fallback: test the 4h timeframe where the NATURAL bar size already solves the problem.

At 4h bars:
- `maxBarsHeld=12` (default) = **48 hours of hold time**
- This is squarely in the 24-96h range where ETH mean-reversions from EMA200-extreme typically complete
- No parameter extension needed — the structural constraint is removed by the timeframe choice itself

Additionally, 4h bars filter out intraday noise that causes frequent false-signals on 1h bars. ETH 1h has many short-lived excursions from EMA200 that reverse within 2-4 hours (before the 12-bar limit); 4h bars are less susceptible to this noise.

## Mechanism

MomentumMeanReversionEngine LONG entry:
- `close < EMA200 - extremeAtrMult * ATR` (extreme downside displacement)
- `RSI < rsiOversoldMax` (momentum extreme confirmed)  
- `targetEMA (EMA50) > close` (target is above current price)
- Take-profit: close >= EMA50 (mean-reversion completed)
- Stop-loss: close <= entry - stopAtrBuffer * ATR
- **Hold-time: up to 12 bars = 48 hours** (adequate for ETH reversion from extreme)

MECHANISM: The 4h timeframe naturally provides the same hold duration as extending maxBarsHeld on 1h would, because each 4h bar represents 4x the time. On 4h, the engine has 48h to see the reversion complete. Most ETH moves from EMA200-extreme to EMA50 complete in 1-4 days (6-24 bars on 4h), well within the 12-bar limit.

## Strategy Differentiation

- VBO (production): Bollinger Band breakout — fires when price BREAKS OUT from consolidation
- DCB2: Donchian breakout — same direction (breakout) as VBO
- MMR: EMA200-extreme FADE — fires when price is MAXIMALLY DISPLACED from EMA200 and is expected to REVERT to EMA50
- These are mechanistically OPPOSITE signals. When VBO fires (breakout), MMR would NOT fire (price is just starting to move away from mean). When MMR fires (extreme displacement), VBO would not fire (no consolidation to break out from).

Expected Spearman correlation with VBO: near-zero or negative (opposite market conditions).

## Null-Screen Design

**Engine**: MomentumMeanReversionEngine (strategy_code: `MMR`)  
**Instrument**: ETHUSDT  
**Interval**: 4h  
**n_draws**: 8  
**seed**: 44

**Param ranges** (3 axes):
| Parameter | Low | High | Rationale |
|---|---|---|---|
| extremeAtrMult | 1.5 | 3.0 | Distance from EMA200; on 4h, ATR is larger so range is same as 1h |
| stopAtrBuffer | 0.6 | 1.8 | Stop distance in ATR units |
| rsiOversoldMax | 20 | 35 | RSI threshold for oversold confirmation |

`maxBarsHeld` left at default (12) = 48h naturally.

## Decision Rules

| Verdict | Action |
|---|---|
| EDGE_PRESENT (P75>=1.2, share>=0.25) | Queue full sweep (27-cell grid or TPE) |
| NO_EDGE_DETECTED | Pivot to MMR BTC 1h; or journal ARCHETYPE_EXHAUSTION if all MMR paths fail |
| INSUFFICIENT_DATA | Investigate JVM account_strategy for MMR 4h ETHUSDT |

## Prerequisites

Need MMR ETHUSDT 4h account_strategy to exist. Check with:
`GET /api/v1/account-strategies` on research JVM (:8081).

If missing, operator action needed OR create via research JVM POST /api/v1/account-strategies with enabled=false, simulated=true.

## Success Criteria (V11 Gates)

- n >= 100 trades
- PF 95% CI lower > 1.0  
- PSR >= 0.95 (DSR >= 0.95 with cumulative-trial scaling)
- +20bps slippage still positive
- Walk-forward: stability_verdict = ROBUST
- Annualized return >= 10% net of fees + 20bps slippage
