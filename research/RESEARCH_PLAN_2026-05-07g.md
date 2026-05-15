# Research Plan 2026-05-07g — TPB (TrendPullbackEngine) BTCUSDT 1h

## Hypothesis
**hypothesis_id**: `df907457-28e4-4024-a2df-52fe96563287`  
**code**: tpb-btc-1h-01  
**Pre-registered**: 2026-05-07 (journal entry created before plan)

### Context
BBR BTCUSDT 1h null-screen returned NO_EDGE_DETECTED (8 draws, all PF 0.13-0.23). BBR closed.

`TrendPullbackEngine` (archetype=`trend_pullback`) is a FULLY IMPLEMENTED spec-driven engine that has NEVER been backtested. It is:
- Registered in `StrategyEngineRegistry` (autodiscovered via `@Component`)
- Has a schema file at `strategy-schemas/trend_pullback.schema.json`
- Has a Tuning class reading: adxEntryMin, stopAtrBuffer, tp1R, biasAdxMin/Max, longRsiMin/Max, bodyRatioMin, clvMin/Max, rvolMin, etc.
- Uses composite signal score (RSI 25%, CLV 30%, body 20%, rvol 25%) with minSignalScore=0.55 gate
- Two-leg exit: TP1 at tp1R, runner with phase-trail ATR trailing

### Mechanism
Entry: EMA50 > EMA200 (stack) + price pulls back to EMA20 (within pullbackTouchAtr × ATR) + reclaim candle (close > EMA20, body >= bodyRatioMin, CLV in [clvMin, clvMax], rvol >= rvolMin) + ADX in [adxEntryMin, adxEntryMax] + composite score >= minSignalScore.  
Exit: TP1 leg at tp1R, runner trails via ATR after phase2R/phase3R thresholds.  
Bias: 4h timeframe confirmation (EMA50>EMA200 on 4h).

### Differentiation from TPR (which was NO_EDGE)
1. Composite signal score gate (reduces noise entries)
2. Runner phase-trail (captures extensions, not just flat TP1)
3. Multi-filter entry (body+CLV+volume quality)
4. Bias timeframe (4h filters 1h entries)

### Anti-correlation with production strategies
- VBO: fires on EXPANSION breakout — TPB fires on PULLBACK RETRACEMENT = structurally opposite
- LSR: supply/demand zone reversal — TPB confirms trend continuation = complementary
- VCB: compression breakout — TPB requires established trend structure = different regime

---

## Setup Requirements (pre-null-screen)
1. Create `strategy_definition` row: `strategy_code=TPB, archetype=trend_pullback, spec_jsonb=<defaults>`
2. Create `account_strategy` row: `accountId=1817bef5, strategyCode=TPB, symbol=BTCUSDT, intervalName=1h, enabled=false, simulated=true`

Both are data-plane only, no JVM restart needed. Same pattern as BBR/DCB2 row creation.

---

## Null-Screen Plan

### Parameters
- **strategy_code**: TPB
- **instrument**: BTCUSDT
- **interval_name**: 1h
- **n_draws**: 8
- **seed**: 42

### Axes (3D)
| Axis | Range | Rationale |
|------|-------|-----------|
| `adxEntryMin` | [20, 35] | Entry quality: higher = more selective but fewer entries |
| `stopAtrBuffer` | [0.4, 0.8] | Stop placement: buffer beyond low of entry bar |
| `tp1R` | [1.5, 3.0] | Target R: first leg TP, runner continues past |

### Decision gates
- `EDGE_PRESENT` → proceed to 27-cell grid sweep
- `NO_EDGE_DETECTED` → shelve TPB BTCUSDT 1h, pivot to next (TPB ETH 1h or ETH 4h)
- `INSUFFICIENT_DATA` → investigate (likely account_strategy missing or params rejected)

---

## Sweep Plan (if EDGE_PRESENT)

27-cell 3D grid:
```
adxEntryMin:    [22, 26, 30]
stopAtrBuffer:  [0.45, 0.60, 0.75]
tp1R:           [1.8, 2.2, 2.8]
```

### Stat-rigor gates for SIGNIFICANT_EDGE
- n >= 100 trades
- PF 95% CI lower > 1.0
- +20bps slippage haircut still positive
- PSR >= 0.95 (DSR >= 0.95 with trial scaling)
- Walk-forward ROBUST

### Portfolio gate
- Compute Spearman correlation with LSR/VCB/VBO trade P&L
- `pf_lo_raw × (1 - 0.5 × |max_corr|) > 1.0` required

---

## Decision branches

### If null-screen EDGE_PRESENT:
1. POST /reviews/request {target_kind: "plan", plan: {strategy_code: "TPB", hypothesis_id: "df907457"}}
2. Spawn reviewer subagent
3. On APPROVED: POST /queue with 27-cell sweep

### If null-screen NO_EDGE:
- Journal: TPB BTCUSDT 1h NO_EDGE
- Pivot: TPB ETHUSDT 1h (ETH account_strategy needed) OR consider DCB2 BTCUSDT 1h (needs account_strategy)
- Note in DATA_WISHLIST: need DCB2 BTCUSDT account_strategy for BTC breakout variant

### If SIGNIFICANT_EDGE achieved:
- POST /reviews/request {target_kind: "graduation"}
- On APPROVED: POST /walk-forward
- On ROBUST + annualized_return >= 10%: GOAL HIT

---

## Anti-pattern guards
- Do NOT sweep `minSignalScore` below 0.50 (removes quality filter, becomes TPR equivalent)
- Do NOT test with bias.interval=1h (loses the 4h macro-filter advantage)
- If n < 100 at 1h BTCUSDT, try loosening `adxEntryMin` to 18 or `pullbackTouchAtr` to 0.6

---
*Plan written by quant-researcher 2026-05-07. Hypothesis df907457 pre-registered before this plan.*
