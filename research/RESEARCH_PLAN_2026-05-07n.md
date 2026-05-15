# RESEARCH_PLAN_2026-05-07n — TPB BTCUSDT 1h Null-Screen (Schema Fixed)

## Session Context

**Date**: 2026-05-07 (fire n) — continued 2026-05-09 (new session)  
**Prior session cleanup**: BBR ETH 1h abandoned (journal c6ed8035) per operator; BBR archetype fully closed.  
**Operator actions completed**:
1. `trend_pullback.schema.json` fixed: `pullbackAtrTolerance` -> `pullbackTouchAtr`, `tpR` -> `tp1R` (matches TrendPullbackEngine.java:519-547)
2. Research JVM rebuilt and restarted with new schema
3. TPB BTCUSDT 1h account_strategy exists (created earlier today)
4. DCB2 BTCUSDT 4h account_strategy created: `74625528-c1e0-4aee-afd0-4fab1288317f`

## Hypothesis

**hypothesis_id**: `df907457-28e4-4024-a2df-52fe96563287`  
**pre-registered**: 2026-05-07T06:29:42 (journal_id `df907457`)  
**strategy_code**: TPB  
**instrument**: BTCUSDT  
**interval_name**: 1h

TPB (TrendPullbackEngine) has never been tested with the correct schema parameters. Prior sessions showed "degenerate" behavior because `tpR` and `pullbackAtrTolerance` were silently ignored (schema rejected them, engine ran at defaults). With the schema fix, parameter sweeps will now actually vary `tp1R` and `pullbackTouchAtr`.

## Mechanism

TrendPullbackEngine:
- EMA50 > EMA200 trend stack confirmation (bullish bias)
- Price pulls back to EMA20 within `pullbackTouchAtr` * ATR tolerance
- Reclaim candle: close > EMA20 with body ratio, CLV, and relative volume quality filters
- Composite signal score >= 0.55 gate
- Two-leg exit: TP1 at `tp1R`, runner with ATR-based phase trail
- 4h bias filter: checks 4h EMA stack from within the 1h backtest (may cause frequency starvation - see 4h hypothesis)

## Null-Screen Plan (3D: adxEntryMin x stopAtrBuffer x tp1R)

All three params are in the valid schema. Parameter ranges:
- `adxEntryMin`: [20, 35] float - trend quality gate at entry
- `stopAtrBuffer`: [0.4, 0.8] float - stop placement below EMA20 in ATR units  
- `tp1R`: [1.5, 3.0] float - reward-to-risk for first take-profit leg

N_draws=8, seed=42, interval=1h, instrument=BTCUSDT

## Expected Outcome

The 4h bias filter may still cause frequency starvation (n=2-20 trades). If this happens:
- Verdict will likely be INSUFFICIENT_DATA (not enough trades) or NO_EDGE_DETECTED
- If INSUFFICIENT_DATA: pivot immediately to TPB BTCUSDT 4h (hypothesis 4324fe63) which removes the cross-timeframe conflict
- If NO_EDGE_DETECTED: still try 4h to confirm (structural mechanism may improve on 4h)
- If EDGE_PRESENT: proceed to full 27-cell grid sweep (adxEntryMin x stopAtrBuffer x tp1R)

## Next Steps (if 1h fails)

1. Run TPB BTCUSDT 4h null-screen (hypothesis `4324fe63-d905-4002-8595-765d35afc5dd`)
2. If 4h also fails: DCB2 BTCUSDT 4h null-screen (account_strategy `74625528-c1e0-4aee-afd0-4fab1288317f`)
   - DCB2 BTC 4h is a DIFFERENT timeframe from the discarded DCB2 BTC 1h (NO_EDGE)
   - Needs to confirm reviewer treats as distinct hypothesis before queueing

## Success Criteria (V11 Gates)

- n >= 100 trades
- PF 95% CI lower > 1.0
- PSR >= 0.95 (DSR >= 0.95 with cumulative-trial scaling)
- +20bps slippage still positive
- Walk-forward: stability_verdict = ROBUST
- Annualized return >= 10% net of fees + 20bps slippage

## Risk Flags

- 4h bias filter may limit trades on 1h: if n < 30 per draw, the null-screen returns INSUFFICIENT_DATA
- This is NOT a re-test of old TPB: schema was broken before, engine silently ran at defaults
- DCB2 BTC 4h: must distinguish from DCB2 BTC 1h (different timeframe = genuinely new hypothesis)

## Portfolio Considerations

TPB fires on EMA pullback reclaim (trend continuation entry). VBO fires on compression breakout (volatility expansion). These are structurally opposite trade types - expected correlation < 0.3. LSR fires on supply/demand zone reclaim - some overlap with TPB (both trend-following) but entry triggers differ significantly (EMA reclaim vs zone reclaim).
