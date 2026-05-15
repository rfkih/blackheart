# Research Plan — 2026-05-06

**Status:** Active — prepared by quant-researcher agent session start.
**Goal:** Find 4th profitable strategy >= 10%/yr net after fees+slippage, walk-forward ROBUST.
**Session date:** 2026-05-06
**Hypothesis ID:** d1459fdf-b80c-44fb-851c-ea677f05d156 (vcb-btc-3d-01)

---

## Premise (5-line state summary)

No strategy has cleared the SIGNIFICANT_EDGE gate in 3 prior sessions (81 total trials, 0 SIG).
Best candidates: VCB BTC 1h PF=1.37 at n=101 (CI_lower=0.847, needs CI_lower>1.0); VBO ETH 15m PF=1.135 n=149.
Discarded: CMR (bull-bleed), FCARRY (sparse data), LSR ETH (n=6), VCB ETH (PF<0.90), MMR (NO_EDGE), TPR (collapses at n>20), BBR (NO_EDGE), VBO ETH 15m without regime gate.
All previous VCB BTC 1h sweeps were single-dimension (1 param each). The interaction space is uncharted.
Null-screen confirms EDGE_PRESENT on 3D interaction space: 8/8 random draws PF>=1.0, max PF=1.275 at n=113.

---

## Constraints reaffirmed

- Instruments: BTCUSDT and ETHUSDT only (Phase 3 plumbed 2026-05-01).
- Intervals: 5m / 15m / 1h / 4h only.
- Production strategies LSR, VCB, VBO untouchable (default params, live deployment, promotion state).
- Strategy bar: 10%/yr net of fees+slippage; ROBUST walk-forward stability.
- Research-mode first: all research runs are enabled=false, simulated=true.
- No deploy-from-spec.sh (operator-only; restarts trading JVM).
- Reviewer approval required before queue and before walk-forward.

---

## Experiment: VCB BTC 1h — 3D Interaction Sweep

### Hypothesis (pre-registered: d1459fdf-b80c-44fb-851c-ea677f05d156)

"The parameters relVolBreakoutMin, bodyRatioBreakoutMin, and longRsiMin jointly define a quality breakout ridge in VCB BTC 1h that is invisible to single-dimension sweeps. A moderate volume threshold (1.15-1.25) combined with a body requirement in the 0.38-0.47 range AND RSI momentum gate (55-62) creates a parameter pocket where n>=100 AND PF_point>=1.6 AND CI_lower>1.0."

Mechanism: relVol ensures institutional participation (not retail noise); bodyRatio ensures directional resolve in the breakout candle; RSI gates momentum quality (mid-range RSI avoids buying exhaustion). The interaction matters because: high-relVol alone allows weak-body breaks; high-body alone includes low-volume retail candles. Sweet spot = medium-high relVol + moderate body (not extreme) + RSI confirming momentum.

### Evidence supporting the hypothesis

- Null-screen 2026-05-06: 8 random draws, all PF>=1.0, mean PF=1.187, max PF=1.275
  - Best draw: relVol=1.1745, body=0.4209, RSI=57 → PF=1.275, n=113
  - Second best: relVol=1.2309, body=0.401, RSI=58 → PF=1.238, n=111
  - Highest body (0.534) → PF=1.069 (lower edge — confirms body shouldn't be too tight)
  - RSI=64 → n=78 (drops below 100, confirms RSI ceiling at ~62)
- Prior single-dim best: longRsiMin=58 → n=101, PF=1.37, CI_lower=0.847 (nearly there)
- Key insight: null-screen draws cluster around relVol=1.17-1.23, body=0.40-0.42, RSI=57-60

### Sweep design

Strategy: VCB | Instrument: BTCUSDT | Interval: 1h

**Axis 1 — relVolBreakoutMin** (volume confirmation gate):
- Values: "1.15", "1.20", "1.25"
- Rationale: null-screen sweet spot 1.17-1.23; 1.15 is looser (more n), 1.25 is tighter (quality)

**Axis 2 — bodyRatioBreakoutMin** (directional candle strength):
- Values: "0.38", "0.42", "0.47"
- Rationale: null-screen best draws at 0.40-0.42; looser (0.38) may boost n; tighter (0.47) may lift PF

**Axis 3 — longRsiMin** (RSI momentum gate):
- Values: "55", "58", "62"
- Rationale: null-screen best draws at RSI=57-60; 62 risks n dropping to 78; 55 captures more momentum

**Total cells:** 27 (3x3x3 complete grid)

### V11 success criteria

- n >= 100 in at least 1 cell
- PF 95% CI lower bound > 1.0 in at least 1 cell (requires PF_point ~1.5-1.7 at n=100)
- +20bps slippage net PnL > 0 in the passing cell
- Walk-forward stability_verdict = ROBUST (after graduation review approval)

### Iteration budget

- 9 iterations per session (3x3 grid subsets via orchestrator iteration logic)
- Max 27 total cells

### Decision branches

**If any cell achieves CI_lower > 1.0 AND n >= 100:**
  → Request graduation review → walk-forward

**If all cells PF in 1.0-1.4 range but no CI_lower > 1.0:**
  → Hypothesis partially supported: edge exists but CI too wide at n=100
  → Pivot: consider VBO BTC 4h (untested interval) OR request developer to add VCB BTC 5m account_strategy

**If all cells PF < 1.0 OR n < 80:**
  → Hypothesis falsified
  → Journal STRATEGY_OUTCOME, update HYPOTHESIS to FALSIFIED
  → Pivot: VBO BTC 4h sweep (interval 4h for VBO is completely untested; 4h reduces noise)

**If walk-forward gives OVERFIT/INCONSISTENT:**
  → Journal lesson, try larger body threshold or wider RSI range to improve robustness
  → Second walk-forward attempt allowed if a plateaued region (not cliff) is found

---

## Backup Experiment: VBO BTC 4h (queued if VCB fails)

**Hypothesis:** VBO on BTC 4h interval has fewer but higher-quality compression-breakout signals than 15m. Noise is lower at 4h. BB-width compression at 4h represents meaningful market consolidation (not random micro-noise). Expected: n=30-50 trades in 17 months, PF>=1.8 in best cells.

**Problem:** n=30-50 is below the n>=100 threshold. This experiment can only pass the stat-sig gate if:
1. n >= 100 (unlikely at 4h with strict compression filter)
2. OR we find that 4h VBO on BTC gives n>=100 with much looser filters

**Pre-test check needed:** Confirm how many 4h bars have BB compression (close/count) before committing to a sweep. If n_draws < 100 at max 8 random draws, mark as BLOCKED and pivot.

---

## Execution order

1. (DONE) Pre-register hypothesis `vcb-btc-3d-01` (journal_id: d1459fdf-b80c-44fb-851c-ea677f05d156)
2. (DONE) Run null-screen → EDGE_PRESENT confirmed on VCB BTC 1h 3D space
3. Write this research plan
4. Submit plan review request to quant-reviewer (POST /reviews/request)
5. Await reviewer APPROVED verdict
6. POST /queue with 27-cell sweep (hypothesis_id=d1459fdf-b80c-44fb-851c-ea677f05d156)
7. POST /tick repeatedly (up to 27 iterations)
8. If SIGNIFICANT_EDGE: POST /reviews/request graduation, await approval, POST /walk-forward
9. If walk-forward ROBUST AND return>=10%: GOAL HIT — journal and exit
10. If no SIGNIFICANT_EDGE after 27 cells: journal STRATEGY_OUTCOME, pivot to VBO BTC 4h null-screen

---

## Decision criteria for next session

If walk-forward ROBUST AND annualized_return >= 10%: GOAL HIT, exit.
If no SIGNIFICANT_EDGE in VCB 3D: check VBO BTC 4h null-screen verdict.
If VBO 4h null-screen NO_EDGE: update DATA_WISHLIST with specific data plumbing needs (VCB BTC 5m requires new account_strategy — operator task).

---

## Blocked items (unchanged from 2026-05-03)

| Blocker | Description | Required Fix |
|---|---|---|
| DCB2 engine bug | DonchianBreakoutEngine fix in working tree but JVM not restarted | Operator: restart research JVM |
| slope_200 feature | Not in FeatureStore | Developer: add to ResearchFeatureEnrichmentService |
| VCB BTC 5m | No account_strategy deployed at 5m interval | Operator: deploy VCB BTC 5m via deploy-from-spec.sh |

---

## Discarded space (do not re-sweep)

| Strategy | Interval | Instrument | Axis-set discarded | Reason |
|---|---|---|---|---|
| CMR | 1h | ETHUSDT | adxMaxForChop x rsiOversoldMax x stopAtrBuffer | Bull-regime structural |
| FCARRY | 1h | BTCUSDT | entryZ x exitZ x holdMaxBars | Insufficient data (n=65) |
| VBO | 15m | ETHUSDT | atrExpansionMin x rvolMin | Bull-regime bleed |
| VBO | 15m | ETHUSDT | tp1R x minSignalScore x atrExpansionMin | Same — slope gate blocked |
| TPR | 1h | ETHUSDT | all tested axes | PF collapses to 0.58 when n rises; n=13 cherry-pick |
| TPR | 1h | BTCUSDT | adxEntryMin x rsiEntryMax x stopAtrMult | PF=0.64 negative in both regimes |
| LSR | 1h | ETHUSDT | adxTrendingMin x longSweepRvolMin x tp1RLongSweep | n=6 trade-starved |
| VCB | 1h | ETHUSDT | adxMaxForChop x rsiOversoldMax x stopAtrBuffer | PF 0.69-0.90 bull-bleed |
| MMR | 1h | BTCUSDT | maxBarsHeld x extremeAtrMult x rsiOversoldMax | NO_EDGE confirmed |
| BBR | 1h | BTCUSDT | stopAtrBuffer x rsiOversoldMax x minRR | NO_EDGE on most cells |

---

*Written by quant-researcher agent — 2026-05-06*
*Hypothesis ID: d1459fdf-b80c-44fb-851c-ea677f05d156*
*Null-screen result: EDGE_PRESENT (8/8 draws PF>=1.0, mean=1.187, max=1.275)*
