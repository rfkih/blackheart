# Research Plan — 2026-05-07

**Status:** Active — prepared by quant-researcher agent session start.
**Goal:** Find 4th profitable strategy >= 10%/yr net after fees+slippage, walk-forward ROBUST.
**Session date:** 2026-05-07
**Hypothesis ID:** cf5f3d38-13be-4fd1-94ea-abd2bed699a1 (dcb2-eth-01)

---

## State Read (cold-boot findings)

### Infrastructure
- Orchestrator: HEALTHY (db=true, jvm=true)
- Research JVM jar: rebuilt 2026-05-06 06:31 — AFTER DCB2 engine fix landed in working tree
- Queue: EMPTY (no PENDING items)
- Leaderboard SIGNIFICANT_EDGE count: 0

### Session history
- 84+ iterations completed across all sessions, 0 SIGNIFICANT_EDGE
- 2026-05-06 session conclusion: ARCHETYPE_EXHAUSTION — all deployed strategy+instrument+interval combos exhausted, no SIG found
- Best historical result: VCB BTC 1h PF=1.37 n=101 CI_lower=0.847 — INSUFFICIENT_EVIDENCE (CI_lower must be > 1.0)

### Blockers previously identified
| Blocker | Status | Evidence |
|---|---|---|
| DCB2 engine bug (JVM not restarted) | LIKELY RESOLVED — jar rebuilt 2026-05-06 | build/libs jar timestamp 2026-05-06 06:31 |
| slope_200 feature (VBO ETH 15m BEAR gate) | STILL BLOCKED | No dev work journal entry found |
| VCB BTC 5m | STILL BLOCKED | No new account_strategy |

### Discarded / falsified space
| Strategy | Interval | Instrument | Reason |
|---|---|---|---|
| CMR | 1h | ETHUSDT | Bull-regime structural (PF 0.08-0.54) |
| FCARRY | 1h | BTCUSDT | Sparse data (n=65) |
| VBO | 15m | ETHUSDT | Bull-regime bleed (slope_200 gate blocked) |
| LSR | 1h | ETHUSDT | n=6 trade-starved (BTC-specific confluence) |
| VCB | 1h | ETHUSDT | PF 0.69-0.90 bull-bleed |
| MMR | 1h | BTCUSDT | NO_EDGE confirmed |
| BBR | 1h | BTCUSDT | NO_EDGE, no account_strategy on ETH |
| TPR | 4h | BTCUSDT | NO_EDGE (n=0-5 trade-starved) |
| LSR | 4h | BTCUSDT | NO_EDGE (n=0) |
| VBO | 1h | ETHUSDT | NO_EDGE (null-screen) |
| VBO | 4h | BTCUSDT | EDGE_PRESENT (null-screen) but n=4-6 (trade-starved, blocked) |
| VCB | 4h | BTCUSDT | INCONCLUSIVE (null-screen), n=21-32 (trade-starved, blocked) |
| DCT | 4h | BTCUSDT | ~10%/yr no margin, discarded (strategy removed) |
| ORB | 15m | BTCUSDT | No edge, session-anchor doesn't work on 24/7 crypto |
| VCB | 1h | BTCUSDT | 27-cell 3D sweep: all INSUFFICIENT_EVIDENCE, best PF=1.341 CI_lower=0.84 |

---

## Premise

**DCB2 (Donchian Breakout 2) on ETHUSDT 1h is the only viable unblocked archetype remaining.**

The DonchianBreakoutEngine had a critical bug (entry condition was logically impossible: prevClose > prevDonchianUpper20 can never be true since prevClose <= prevHigh <= prevDonchianUpper20). The bug was fixed in the working tree by 2026-05-03 and the research JVM jar was rebuilt on 2026-05-06. This is the first sweep of DCB2 with a functional engine.

**Why DCB2 on ETH 1h specifically:**
1. ETHUSDT 1h has ~20k bars (~17 months) — same as all prior ETH tests, but the entry condition change now makes trades possible
2. The dataset is BULL-dominated (2024-2026), which favors momentum-continuation strategies like Donchian breakout
3. ETH has higher idiosyncratic volatility than BTC — Donchian breakouts should be more frequent and have larger follow-through
4. ADX lessons from strategy_research_lessons.md (#5: ADX-rising filter adds +46% per-trade R) are incorporated

---

## Experiment: DCB2 ETH 1h — 3D Sweep

### Hypothesis (pre-registered: cf5f3d38-13be-4fd1-94ea-abd2bed699a1)

"DCB2 (Donchian breakout + rvol + ADX filters) on ETHUSDT 1h with fixed engine will produce n>=100 trades across broad param ranges. The 2024-2026 BULL-dominated dataset favors this momentum-continuation archetype. At least 1 of 27 cells achieves n>=100 AND PF_point>=1.3 AND PF 95% CI_lower>1.0 AND +20bps slippage net positive."

Mechanism: close[t] > donchianUpper20 of bars [t-21..t-1] (not including current bar) confirms upside breakout from prior 20-bar range. rvol gate requires institutional-volume participation; ADX gate requires trending context (not flat chop). This is a BULL-aligned strategy, appropriate for the dataset.

### Evidence base
- Prior DCT (BTC 4h) achieved ~10%/yr — same archetype, different surface
- ADX-rising filter reuse from lessons memory
- BULL-dominated dataset (2024-2026) favors momentum strategies
- ETH 1h has 4x more bars than BTC 4h (DCT's surface), clearing the n>=100 gate at looser param settings
- **DB-confirmed signal count (2026-05-07 session):** SQL query against feature_store confirms 960 bars where close > prev.donchianUpper20; 606 bars after rvolMin>=1.30 AND adx>=18 filter; 528 of those also pass maxEntryRiskPct=0.04 gate (87.1% pass rate). There ARE valid signals in the data — engine returning n=0 is a JVM/deployment issue, not a data issue.
- **JAR timeline:** Engine fix committed 2026-05-07 00:06 UTC+7. JAR rebuilt 2026-05-06 06:31. Prior n=0 iterations (iters 1-2) ran 2026-05-03 — PRE-FIX. Current JAR is post-fix.

### Sweep design

Strategy: DCB2 | Instrument: ETHUSDT | Interval: 1h

**Axis 1 — rvolMin** (institutional volume gate):
- Values: "1.10", "1.30", "1.50"
- Rationale: 1.10 = loose (more n but lower quality), 1.50 = tight (fewer but stronger)
- Note: DCT lesson #3 says rvolMin=1.10 cuts tail winners; 1.30 was better post-hoc on BTC 4h

**Axis 2 — adxEntryMin** (trending-context gate):
- Values: "18", "22", "26"
- Rationale: 18 = broad trend acceptance, 26 = strong trend required (reduces chop false-breakouts)

**Axis 3 — tpR** (profit-target, R-multiple):
- Values: "1.5", "2.0", "2.5"
- Rationale: tpR controls reward/risk ratio. Lower = more wins but smaller, higher = fewer but larger
- DCT lesson #2: tightening stop below 3.0 ATR hurts, so here we vary tpR not stopAtr

**Total cells:** 27 (3x3x3 complete grid, all 3 axes)

### V11 success criteria (all required for graduation)

- n >= 100 in at least 1 cell
- PF 95% CI lower bound > 1.0 in the passing cell
- +20bps slippage net PnL > 0 in the passing cell
- DSR >= 0.95 (with cumulative trial scaling)
- Walk-forward stability_verdict = ROBUST (after graduation review approval)

### Falsification criteria

- Engine still broken: if all 27 cells return n<20, engine fix is not deployed and sweep must be PARKED
- Architecture blocked: if n>=20 but PF<0.9 across all cells, archetype has no edge on ETH 1h
- Success: at least 1 cell meets all 5 V11 gates above

---

## Backup hypotheses (if DCB2 is falsified)

**Priority 2 — LSR BTC 1h: volume-loosened axis**
The null-screen on LSR BTC 1h (2026-05-06) showed EDGE_PRESENT with PF=3.75 but n=12 (identical across all draws — suspicious, params insensitive). The null-screen used minSignalScoreLongSweep [0.30-0.50]. A sweep varying minSignalScoreLongSweep more aggressively (0.10-0.30 range, very loose) may produce n>=100 with remaining edge. CAUTION: LSR live params must not be touched; only overrides are swept. Risk: n=12 even at loose params suggests LSR BTC 1h may be fundamentally trade-starved on this axis-set.

**Priority 3 — VBO BTC 15m: alternative param regions**
VBO is live and profitable on BTC 15m. There may be alternative param regions not yet swept. However, the leaderboard shows 0 SIGNIFICANT_EDGE items, and the prior LSR/VCB BTC sweeps show all INSUFFICIENT_EVIDENCE, suggesting the BTC 1h surface is genuinely constrained. This is lowest confidence but has highest residual probability of success given VBO's proven live edge.

**If all 3 priority hypotheses fail:**
- Journal ARCHETYPE_EXHAUSTION_2026-05-07 with DATA_WISHLIST
- DATA_WISHLIST items: slope_200 feature (for VBO ETH 15m BEAR gate), DCB2 BTC 1h (currently no account_strategy), VBO BTC 4h (trade-starved at n=4-6)

---

## Execution order

1. (DONE) Pre-register hypothesis dcb2-eth-01 (journal_id: cf5f3d38-13be-4fd1-94ea-abd2bed699a1)
2. Write this research plan (in progress)
3. Submit plan review request to quant-reviewer (POST /reviews/request)
4. Await reviewer APPROVED verdict
5. POST /queue with 27-cell sweep (hypothesis_id=cf5f3d38-13be-4fd1-94ea-abd2bed699a1)
6. POST /tick repeatedly (up to 27 iterations), monitoring for early n=0 (engine still broken signal)
7. If any cell n=0 across first 3 ticks: PARK queue, journal JVM still broken, escalate to operator
8. If SIGNIFICANT_EDGE: POST /reviews/request graduation, await approval, POST /walk-forward
9. If walk-forward ROBUST AND return>=10%: GOAL HIT — journal and exit
10. If no SIGNIFICANT_EDGE after 27 cells: journal STRATEGY_OUTCOME, pivot per backup plan above

---

## Risk controls

- **Engine sanity check:** Monitor n across first 3 iterations. If n<20 consistently, park and escalate.
- **Protected strategies:** DCB2 has its own strategy_code; no overlap with LSR/VCB/VBO default params.
- **Portfolio correlation gate:** Orchestrator V11 automatically demotes to INSUFFICIENT_EVIDENCE if daily-return correlation with LSR/VCB/VBO book is too high. This is an automatic guard.
- **No deploy:** This sweep is research-mode only. deploy-from-spec.sh is operator-only.

---

*Written by quant-researcher agent — 2026-05-07*
*Hypothesis ID: cf5f3d38-13be-4fd1-94ea-abd2bed699a1*
*Prior null-screen: N/A — using engine-fix evidence + DCT lesson transfer*
