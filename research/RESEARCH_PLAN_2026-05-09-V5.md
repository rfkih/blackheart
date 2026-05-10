# DCB BTCUSDT 4h Grid V5 Research Plan
**Date:** 2026-05-09  
**Session:** 6 (continuation of session 5)  
**Hypothesis:** b74a4820-29ea-4e1f-954f-57b3174464c3  
**Strategy:** DonchianBreakoutEngine (DCB), BTCUSDT, 4h interval

---

## Prior Context Summary

Grid V3 (c91a409c) and Grid V4 (ca61de33) completed 56 iterations across:
- adxEntryMin=[21,22,23], rvolMin=[1.2,1.3,1.4], tpR=[2.0,2.5,3.0], stopAtrMult=[2.0,2.3]

**Best result found**: iter 51 = adxEntryMin=22, rvolMin=1.3, tpR=2.0, stopAtrMult=2.0
- n=114, PF=1.505, CI_lo=**0.998** (just below 1.0 threshold)
- ALL regimes positive: BEAR+1.74, BULL+2.07, NEUTRAL+0.04
- Win rate=48.2%, calmar=2.91, max_drawdown=1.33%

**Gap to SIGNIFICANT_EDGE**: CI_lo needs to be >1.0 (currently 0.998). Need PF ~1.60+ or n ~130+.

**Key patterns confirmed across 56 iterations:**
1. tpR=2.0 consistently dominates (tpR=2.5/3.0 kills NEUTRAL regime → negative)
2. stopAtrMult=2.0 produces better n counts than 2.3 (fewer early stops → more n)
3. adxEntryMin=22 gives better PF than 21 (more selective) with comparable n
4. NEUTRAL regime (+/-) is the key discriminator — only tpR=2.0 keeps it positive
5. rvolMin=1.3 is the sweet spot (rvolMin=1.4 slightly fewer trades, similar PF)

**V4 budget ran out** before testing: adxEntryMin=22/rvolMin=1.4, adxEntryMin=23 cells

---

## V5 Sweep Design

### Core hypothesis
Early break-even activation (breakEvenR=0.5-0.8) converts what would be losses in NEUTRAL regime into small wins. NEUTRAL regime +0.04 at best = barely positive. If we shift break-even earlier, choppy mid-trend trades get locked to breakeven rather than full loss → raises NEUTRAL PF → raises overall PF → CI_lo>1.0.

### Grid V5 Parameters (5-dimensional sweep)

```
adxEntryMin: [22, 23]
rvolMin:     [1.3, 1.4]  
tpR:         [1.7, 2.0]
breakEvenR:  [0.5, 0.75, 1.0]
stopAtrMult: [2.0]        (fixed at confirmed optimum)
```

Total cells: 2 × 2 × 2 × 3 × 1 = **24 cells**

Rationale for each dimension:
- adxEntryMin=22 (confirmed best); adxEntryMin=23 (may give higher PF at some cost of n)
- rvolMin=1.3-1.4 (confirmed range; V4 ran out before testing 1.4@adxEntryMin=22)
- tpR=1.7 (new, lower than 2.0 — shorter hold = more trades in NEUTRAL)
- tpR=2.0 (confirmed best with break-even=1.0; how does it interact with earlier BE?)
- breakEvenR=0.5 (very early BE activation — protects at 50% of stop distance)
- breakEvenR=0.75 (intermediate)
- breakEvenR=1.0 (baseline — all prior sweeps used this)
- stopAtrMult=2.0 (fixed)

### Sweep order (grid, left-to-right product):
adxEntryMin cycles slowest → all [22] cells first, then [23]

### Iter budget: 30 ticks (24 cells + 6 for overruns)

---

## Decision Branches

### If any cell achieves CI_lo > 1.0 with all-regime-positive:
→ Request graduation review immediately
→ POST /walk-forward with that iteration_id
→ If ROBUST and annualized_return >= 10%: GOAL HIT

### If best CI_lo in 0.95-1.0 range:
→ Design V6 with even finer breakEvenR granularity (0.3, 0.5, 0.7) around best cell
→ Consider adding maxBarsHeld=[24, 36] dimension

### If CI_lo stays below 0.95 across all breakEvenR values:
→ The breakEvenR axis does not help sufficiently
→ Pivot to adxEntryMin=23 zone or extend date range backward (if orchestrator supports)

### If SIGNIFICANT_EDGE achieved but walk-forward NOT ROBUST:
→ Journal STRATEGY_OUTCOME, note regime sensitivity
→ Try 1h interval (more trades per period) OR ETH (when backfilled)

---

## Risk factors
- breakEvenR=0.5 may reduce n substantially (many exits at break-even → fewer full wins → lower PF?)
- adxEntryMin=23 may drop n below 100 (estimated n=80-100 at adxEntryMin=23, rvolMin=1.3)
- DSR penalty is now at cumulative_trials=56 — hitting 57-80 → DSR will trend downward; need PF to compensate

---

## Review checklist
Plan is a continuation of approved hypothesis mechanism (Donchian breakout, same engine).
New axis (breakEvenR) does not change the entry logic, only exit management.
4 dimensions tested (adxEntryMin, rvolMin, tpR, breakEvenR) — satisfies ≥3 axis rule.
Prior sweeps V3+V4 confirm EDGE_PRESENT foundation; V5 is convergent refinement.
