# RESEARCH_PLAN_2026-05-07c
**Hypothesis ID:** 119bd786-3737-42c1-8c53-560007bbad42  
**Hypothesis code:** dcb2-eth-03  
**Date:** 2026-05-07  
**Session:** Continuation after DCB2 ETH 1h archetype exhaustion  

---

## Context: Why DCB2 ETH 15m

The 1h backtest (2 sweeps, 57 cells total) confirmed DCB2 has real edge on ETH (PF=1.13, CI_lower=0.857). The CI failure is statistical, not economic: n~294 at 1h with high per-trade variance (breakout payoff distribution is right-skewed). At 15m, same setup fires ~4x more often, expected n~1200. Bootstrap CI half-width scales as 1/sqrt(n), so CI narrows ~50%. If PF holds at 15m, CI_lower crosses 1.0.

This is the minimum-assumption next step: same edge, same parameters, more data.

---

## Hypothesis

**Mechanism:** DCB2 (DonchianBreakoutEngine) on ETHUSDT 15m interval.  
- Close[t] > prev.donchianUpper20 (20-bar Donchian channel breakout)
- ADX >= 26 (trending market filter)
- RelVol >= 1.10 (volume confirmation)
- ATR-based stop, take-profit, break-even

**Prediction:** At 15m with adxEntryMin=26, rvolMin=1.10, the larger sample (n~1200) will produce CI_lower > 1.0 in at least 3 of 12 sweep cells.

**Mechanism link:** More data from same market structure → narrower CI → statistical significance without changing the underlying edge.

**Fixed parameters (proven optimal from 1h sweep):**
- adxEntryMin = 26
- rvolMin = 1.10

**Variable parameters (3D sweep, 12 cells):**
- stopAtrMult: [2.0, 2.5, 3.0] — include default (3.0) that showed best PF at 1h
- breakEvenR: [0.7, 1.0] — two values (tighter and default)
- tpR: [2.0, 2.5] — two values (per 1h sweep: tpR=2.5 consistently stronger)

Total cells: 3 x 2 x 2 = 12

---

## V11 Success Criteria

- n >= 100 per cell (expected ~1200; fail if < 100)
- PF 95% CI lower > 1.0 in at least 1 cell
- +20bps slippage still positive in passing cell(s)
- DSR >= 0.95 with cumulative trial scaling
- Walk-forward stability_verdict = ROBUST (after graduation review)

---

## Falsification Conditions

1. **PF point estimate < 1.05 across all 12 cells**: Edge has washed out at 15m (perhaps 15m is too noisy for Donchian breakouts). Shelve DCB2 entirely — both 1h and 15m tested.
2. **n < 100 at all cells after 15m filter**: The ADX/rVol filters cut too many 15m bars. Evidence that the filters are calibrated for 1h, not 15m.

---

## Decision Branches

| Outcome | Action |
|---|---|
| >=1 cell CI_lower > 1.0 | Request graduation review, POST /walk-forward |
| PF 1.05-1.12 but CI_lower < 1.0 | Edge present but underpowered even at 15m; DATA_WISHLIST (need longer history) |
| PF < 1.05 across all cells | DCB2 SHELVED; pivot to VBO ETH 15m null-screen |
| n < 100 | Filters over-constrain 15m; try with adxEntryMin=22 |

---

## Sweep Configuration

```json
{
  "strategy_code": "DCB2",
  "interval_name": "15m",
  "instrument": "ETHUSDT",
  "sweep_config": {
    "strategy": "grid",
    "params": [
      {"name": "adxEntryMin", "values": ["26"]},
      {"name": "rvolMin", "values": ["1.10"]},
      {"name": "stopAtrMult", "values": ["2.0", "2.5", "3.0"]},
      {"name": "breakEvenR", "values": ["0.7", "1.0"]},
      {"name": "tpR", "values": ["2.0", "2.5"]}
    ]
  },
  "hypothesis_id": "119bd786-3737-42c1-8c53-560007bbad42",
  "iter_budget": 12,
  "early_stop_on_no_edge": true,
  "require_walk_forward": true
}
```

---

## Review Gate

Plan axis_names for review hash: ["adxEntryMin", "rvolMin", "stopAtrMult", "breakEvenR", "tpR"]  
(Same 5-param set as sweep 2. Hash will differ due to different strategy_code/hypothesis_id.)

---

## Connection to Mission

DCB2 ETH 15m is the highest-probability path forward given:
1. Confirmed real edge (PF=1.13) at 1h that only fails on statistical power
2. 4h and 1h intervals exhausted for ETH; 15m gives 4x more signals
3. Same parameters proven in 1h sweep transfer directly
4. Does not touch LSR/VCB/VBO baseline

If this sweep fails, next archetype: VBO ETH 15m (null-screen suggested NO_EDGE but different timeframe possible) or DCB2 BTC 1h (requires operator to create account_strategy).
