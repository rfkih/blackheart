# RESEARCH_PLAN_2026-05-07d
**Hypothesis ID:** 68d27535-834f-4a33-9554-9fd98ea5f35c  
**Hypothesis code:** dcb2-eth-4h-01  
**Date:** 2026-05-07  
**Session:** New hypothesis after archetype exhaustion — DCB2 ETH 4h discovered via fresh null-screen

---

## Context: Why DCB2 ETH 4h

Previous sessions exhausted all known strategy+instrument+interval combinations. This session ran two new null-screens:

1. **VBO BTC 4h (loose)**: EDGE_PRESENT but n=11-19 — structurally trade-starved even at compressionBbWidthPctMax=0.20
2. **VBO ETH 4h**: EDGE_PRESENT (PF=2.3-3.8, 100% draws>=1.2) but n=5-11 — more severely trade-starved

Then pivoted to DCB2 ETH 4h — **never previously tested**. DCB2 has confirmed real edge on ETH 1h (PF=1.13) but insufficient n (~294). At 4h, with tighter stopAtrMult, trades resolve faster producing more completed trade records per unit time.

**DCB2 ETH 4h null-screen result:**
- EDGE_PRESENT: P75=1.47, mean=1.37, 87.5% draws PF>=1.2
- Crucially: **n=104 (PF=1.28) and n=128 (PF=1.24)** achievable at low stopAtrMult (~1.5)
- Higher stopAtrMult (2.3-2.6) gives n=23-51 (not enough)
- adxEntryMin=22-26 consistently positive; adxEntryMin=18 also positive but lower quality

---

## Hypothesis

**Mechanism:** DCB2 (DonchianBreakoutEngine) on ETHUSDT 4h interval.
- Close[t] > prev.donchianUpper20 (20-bar Donchian channel breakout on 4h bars)
- ADX >= 22 or 26 (macro trending market filter)
- RelVol >= 0.90-1.30 (volume confirmation)
- ATR-based stop/TP at 4h timeframe

**Key mechanistic argument for 4h vs 1h:**
- 4h bars represent macro institutional flow (vs 1h microstructure noise)
- Donchian breakout at 4h means a 80h (3.3 day) channel is broken — significant price discovery event
- Tight stopAtrMult (1.3-1.8) works at 4h because signal-to-noise is higher; 1h required larger multiples (2.5-3.0) to avoid noise stops
- Result: more completed trades per year at 4h than 1h despite fewer bars

**Prediction:** At least 3 of 18 cells will have n>=100 AND PF CI_lower > 1.0.

**Fixed context (from null-screen):** stopAtrMult must be <=1.8 to achieve n>=100.

**Variable parameters (3D sweep, 18 cells):**
- stopAtrMult: [1.30, 1.50, 1.80] — targeting the n>=100 regime
- adxEntryMin: [22, 26] — both showed positive edge in null-screen
- rvolMin: [0.90, 1.10, 1.30] — volume confirmation axis

Total cells: 3 x 2 x 3 = 18

---

## V11 Success Criteria

- n >= 100 per cell (achievable based on null-screen evidence: n=104/128 at stopAtrMult~1.5)
- PF 95% CI lower > 1.0 in at least 1 cell
- +20bps slippage still positive in passing cell(s)
- DSR >= 0.95 with cumulative trial scaling
- Walk-forward stability_verdict = ROBUST (after graduation review)

---

## Falsification Conditions

1. **n < 60 across all 18 cells**: Breakout signals at 4h too sparse even with these filters. DCB2 ETH exhausted across all intervals (1h, 15m, 4h).
2. **PF < 1.05 across all cells with n>=100**: Edge vanishes when measured on adequate sample. Pivot to DCB2 BTC 4h null-screen or request DCB2 BTC account_strategy.
3. **CI_lower < 1.0 across all cells**: Same statistical power problem as 1h, but at 4h interval. Journal as DATA_WISHLIST for longer backtest window.

---

## Decision Branches

| Outcome | Action |
|---|---|
| >=1 cell n>=100 AND CI_lower > 1.0 | Request graduation review, POST /walk-forward |
| n>=100 but CI_lower < 1.0 | INSUFFICIENT_EVIDENCE — shelve DCB2 ETH, pivot to DCB2 BTC 4h null-screen |
| n<60 across all cells | FALSIFIED — DCB2 ETH exhausted; request operator create DCB2 BTC account_strategy |
| PF < 1.05 at n>=100 | DISCARD — archetype has no edge at 4h |

---

## Sweep Configuration

```json
{
  "strategy_code": "DCB2",
  "interval_name": "4h",
  "instrument": "ETHUSDT",
  "sweep_config": {
    "strategy": "grid",
    "params": [
      {"name": "stopAtrMult", "values": ["1.30", "1.50", "1.80"]},
      {"name": "adxEntryMin", "values": ["22", "26"]},
      {"name": "rvolMin", "values": ["0.90", "1.10", "1.30"]}
    ]
  },
  "hypothesis_id": "68d27535-834f-4a33-9554-9fd98ea5f35c",
  "iter_budget": 18,
  "early_stop_on_no_edge": true,
  "require_walk_forward": true
}
```

---

## Review Gate

Plan axis_names for review hash: ["stopAtrMult", "adxEntryMin", "rvolMin"]
Prior DCB2 ETH sweeps used: 1h interval with adxEntryMin x rvolMin x tpR and stopAtrMult x breakEvenR x tpR. This sweep uses 4h interval — new axis combination (4h + stopAtrMult as primary dimension) not previously reviewed.

---

## Connection to Mission

DCB2 ETH 4h is the only remaining untested combination on plumbed data (BTCUSDT/ETHUSDT at 5m/15m/1h/4h) that shows:
1. Confirmed real edge (PF=1.28-1.64 in null-screen draws)
2. Achievable n>=100 (two null-screen draws already show n=104/128)
3. Does not require operator action (DCB2 ETH account_strategy already exists)
4. Completely untested axis (4h interval + tight stopAtrMult combination)
5. Does not touch LSR/VCB/VBO baseline

If this sweep fails, the only remaining paths are:
- DCB2 BTC 4h (needs operator account_strategy creation on BTC)
- Longer backtest window (increases n for DCB2 ETH 1h from ~294)
- New strategy engine (developer task)
