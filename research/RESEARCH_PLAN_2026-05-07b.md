# Research Plan — 2026-05-07b (Second sweep: Exit structure)

**Status:** Active — prepared post-sweep-1 results.
**Goal:** Find 4th profitable strategy >= 10%/yr net after fees+slippage, walk-forward ROBUST.
**Session date:** 2026-05-07
**Hypothesis ID:** 75bf7fcd-ee36-4d3a-acca-b4ceedff7471 (dcb2-eth-02 v2, updated to 3D)

---

## State Read (post-sweep-1)

### Sweep 1 results summary (cf5f3d38, just completed)
- 27 cells: rvolMin=[1.10,1.30,1.50] x adxEntryMin=[18,22,26] x tpR=[1.5,2.0,2.5]
- All cells INSUFFICIENT_EVIDENCE. Final verdict: INSUFFICIENT_EVIDENCE.
- KEY PATTERN: adxEntryMin=26 consistently produces PF=1.09-1.13 regardless of rvolMin and tpR.
- Best cell: rvolMin=1.10, adxEntryMin=26, tpR=2.0 → n=294, PF=1.130, CI_lower=0.845
- adxEntryMin=18-22 → PF < 1.02 universally (noise zone)
- Conclusion: ADX>=26 is the quality filter; PF point estimate is real but distribution is too skewed for CI_lower>1.0 at n=294.

### Why CI_lower fails despite positive PF
Donchian breakouts are inherently right-skewed: many small stop-losses (frequent), few large winners (rare but big). This makes the bootstrap CI wide relative to the mean PF. With PF=1.13 and n=294, the CI is ~[0.845, 1.474]. To get CI_lower>1.0 with this distribution shape, we need EITHER:
1. PF >> 1.5 (hard to achieve by tightening entry)
2. n >> 1000 (structurally impossible on this surface)
3. **Reduced variance in outcomes** — shift the distribution by tightening the stop (fewer huge losses) and moving break-even earlier (convert losses to near-zero outcomes)

### Option 3 is the thesis for this sweep

---

## Premise

**Sweep 2 tests whether exit structure optimization can push CI_lower above 1.0 on the adxEntryMin=26 surface.**

The entry gate is proven (adxEntryMin=26, rvolMin=1.10 gives max-n with best PF). The problem is distribution variance. Two levers:
- `stopAtrMult`: Default=3.0. Reducing to 1.5-2.5 cuts the ATR stop tighter, reducing max loss per trade. This INCREASES win-rate but also increases false-stop-outs. Net PF effect is ambiguous — depends on whether false-stop-outs are recovered.
- `breakEvenR`: Default=1.0. Reducing to 0.5-0.7 moves stop to entry faster. Converts some losses into ~zero-P&L trades, reducing variance of losing trades. Net PF effect: usually reduces PF slightly (fewer full-sized winners) but reduces CI width.

The hypothesis is that at adxEntryMin=26 (high-quality breakouts with momentum), reducing stop size and moving to break-even faster will NOT materially hurt PF (because these are genuine breakouts) while reducing outcome variance sufficiently to push CI_lower > 1.0.

---

## Experiment: DCB2 ETH 1h — Exit Structure Sweep

### Hypothesis (pre-registered: d9c66724-5f4b-4393-b234-fbe9fece20cc)

"DCB2 (Donchian breakout, adxEntryMin=26, rvolMin=1.10, tpR=2.0 FIXED) on ETHUSDT 1h. Sweeping stopAtrMult=[1.5,2.0,2.5] x breakEvenR=[0.5,0.7,1.0] (9 cells). Hypothesis: tighter stops + earlier break-even shift reduce outcome variance sufficiently that at least 1 cell achieves PF 95% CI_lower>1.0 AND n>=100 AND +20bps slippage positive."

### Evidence base
- Prior sweep confirmed adxEntryMin=26 + rvolMin=1.10 gives PF=1.13, n=294
- CI_lower=0.845 with default stopAtrMult=3.0, breakEvenR=1.0
- Tighter stop + earlier BE = lower variance = potentially CI_lower>1.0
- DB-confirmed: 404 signal bars at this gate combo (pre-maxEntryRiskPct filtering)

### Sweep design

Strategy: DCB2 | Instrument: ETHUSDT | Interval: 1h

**FIXED params:** adxEntryMin=26, rvolMin=1.10, tpR=2.0

**Axis 1 — stopAtrMult** (stop distance in ATR units):
- Values: "1.5", "2.0", "2.5"
- Rationale: Default is 3.0. Tighter = smaller max loss, higher win rate, less room for trade to breathe
- Note: Must stay above typical noise level for 1h ETH (~0.5-1.0 ATR); 1.5 is minimum reasonable

**Axis 2 — breakEvenR** (shift stop to entry at this R-multiple):
- Values: "0.5", "0.7", "1.0"
- Rationale: Default is 1.0. Earlier BE = convert losers to near-zero, reduce variance

**Axis 3 — tpR** (profit target in R-multiples):
- Values: "1.5", "2.0", "2.5"
- Rationale: Interaction with stopAtrMult — tighter stop changes actual R-ratio. Lower stop + higher tpR = more favorable reward:risk shape.

**Total cells:** 27 (3x3x3 complete grid)

### V11 success criteria (all required for graduation)
- n >= 100 in at least 1 cell
- PF 95% CI lower bound > 1.0
- +20bps slippage net PnL > 0
- DSR >= 0.95 (with cumulative trial scaling)
- Walk-forward stability_verdict = ROBUST (after graduation review)

### Falsification criteria
- All cells CI_lower < 0.90 (exit structure doesn't reduce variance enough)
- n drops below 100 at tight stop (stopAtrMult=1.5 causing too many false-stops)

---

## Backup hypothesis if sweep 2 fails

**LSR BTC 1h Volume axis**: The null-screen (2026-05-06) showed EDGE_PRESENT at PF=3.75 with n=12. Very trade-starved. A new null-screen varying minSignalScoreLongSweep down to 0.10-0.25 (extremely loose) may produce n>100 with residual edge. Alternatively, a volume-relaxed sweep with longSweepRvolMin=[0.40,0.50,0.60] could unlock more trades.

**DATA_WISHLIST if all paths exhausted:**
- DCB2 BTC 1h: needs operator to CREATE account_strategy (BTCUSDT, 1h, DCB2)
- VBO BTC 4h: confirmed EDGE_PRESENT (PF~1.8) but n=4-6 (trade-starved) — needs VBO 4h account_strategy + longer dataset
- slope_200 feature for VBO ETH 15m BEAR gate

---

## Execution order

1. (DONE) Pre-register hypothesis dcb2-eth-02 (d9c66724-5f4b-4393-b234-fbe9fece20cc)
2. (DONE) Write this research plan
3. Submit plan review request to quant-reviewer (POST /reviews/request)
4. Await reviewer APPROVED verdict
5. POST /queue with 9-cell sweep
6. POST /tick (9 iterations)
7. If SIGNIFICANT_EDGE: POST /reviews/request graduation, POST /walk-forward
8. If no SIGNIFICANT_EDGE: journal outcome, pivot to backup

---

*Written by quant-researcher agent — 2026-05-07*
*Hypothesis ID: 75bf7fcd-ee36-4d3a-acca-b4ceedff7471*
*Prior sweep: cf5f3d38 (27 cells, all INSUFFICIENT_EVIDENCE, best PF=1.130 at adxEntryMin=26)*
