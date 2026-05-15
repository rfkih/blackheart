# RESEARCH_PLAN_2026-05-07m — BBR ETHUSDT 1h: MeanReversionOscillator post archetype-fix

## Hypothesis
**hypothesis_id**: `71aabe12-70d5-45a1-98ba-338b3006a765`  
**pre-registered**: 2026-05-07T07:28:54 (journal_id `71aabe12`)

## Context & Motivation

All primary archetypes have been tested and exhausted (ARCHETYPE_EXHAUSTION_2026-05-07, journal 6122a135). Per the loop rules, continuing with the least-recently-tested non-broken archetype.

**BBR ETH 1h prior history:**
- 2026-05-06, 2026-05-07T05:48: INSUFFICIENT_DATA × 3 (archetype=LEGACY_JAVA bug)
- 2026-05-07T06:08: archetype fix applied (strategy_definition updated to mean_reversion_oscillator)
- 2026-05-07T06:22: BBR BTC 1h null-screen POST-FIX: NO_EDGE (PF=0.13-0.23, n=70-105)

BBR ETH 1h has NEVER been tested with the correct archetype. This is its first true test.

## Mechanism

MeanReversionOscillatorEngine LONG:
1. Previous bar: close <= lower Bollinger Band (outside the band)
2. Current bar: close > previous close (reversal candle)
3. RSI < rsiOversoldMax (momentum exhaustion confirmed)
4. R:R check: (TP - entry) / (entry - stop) >= minRewardRiskRatio
5. Take-profit: middle BB (EMA20)
6. Stop-loss: lower BB - stopAtrBuffer * ATR

MECHANISM: Bollinger Band extreme + RSI extreme + reversal candle = oversold bounce. On ETH 1h, ETH's higher beta means Bollinger Band extremes represent larger absolute moves (1.5-2× BTC's ATR). Larger extreme displacements on ETH may create higher snap-back velocity, improving the win-rate on this signal compared to BTC. Additionally, ETH's higher retail participation means RSI extremes at 1h may be more reliable as capitulation signals.

## Why ETH might outperform BTC

- ETH beta to BTC = 1.2-1.5×: larger amplitude oscillations
- ETH retail-driven moves: more panic selling (RSI drops deeper, more reliable reversal)
- ETH funding rate extremes: correlation with BB extremes may be stronger
- BTC result (PF=0.13-0.23): BTC's more institutional trading base means less panic-driven BB extremes

## Axes (3D, all valid per schema)

| Parameter | Low | High | Rationale |
|---|---|---|---|
| rsiOversoldMax | 18 | 35 | RSI threshold; lower = more extreme oversold required |
| stopAtrBuffer | 0.3 | 1.0 | Stop distance below lower BB in ATR units |
| minRewardRiskRatio | 1.5 | 3.0 | Minimum R:R at entry |

## Decision Rules

| Verdict | Action |
|---|---|
| EDGE_PRESENT (P75>=1.2, share>=0.25) | Proceed to full sweep |
| NO_EDGE_DETECTED | Journal as BBR ETH 1h exhausted; archetype fully dead |
| INSUFFICIENT_DATA | Still an infra/account_strategy issue; escalate to operator |

## Prior performance benchmark

BBR BTC 1h with similar axes: PF=0.13-0.23. If ETH shows PF > 0.5, there's potential. If ETH shows similar PF to BTC (< 0.3), the archetype is confirmed dead across all instruments.

## Success Criteria (V11 Gates)

- n >= 100 trades
- PF 95% CI lower > 1.0  
- PSR >= 0.95 (DSR >= 0.95 with cumulative-trial scaling)
- +20bps slippage still positive
- Walk-forward: stability_verdict = ROBUST
- Annualized return >= 10% net of fees + 20bps slippage
