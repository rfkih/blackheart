# Research Plan 2026-05-12 (Session 13 — MMR 15m completion + residual archetype sweep)

## Session Premise (5-line brief)

**What's currently winning**: No 4th strategy yet. Production LSR/VCB/VBO untouched (+20%/yr each).
**What's discarded**: DCB (4h NOT_ROBUST, 1h/15m NO_EDGE, 5m INSUFFICIENT_DATA-pending-retry); MRO (4h/1h/15m NO_EDGE); MMR (4h/1h NO_EDGE, 15m in-progress); TPB/TPR (freq-starved all intervals); FCARRY (fully exhausted on BTCUSDT).
**What's in-flight**: MMR 15m null-screen — backtest aa18298c RUNNING at 44% as of session start. Prior session context exhausted while null-screen was running. Re-submitted null-screen will not proceed until running backtest completes (JVM: 1 concurrent backtest limit).
**Standing hypothesis**: f90a8a64 (MMR 15m) — expect NO_EDGE (confirming full MMR archetype exhaustion on BTCUSDT).
**Goal**: ≥10%/yr net of fees+20bps slippage, walk-forward ROBUST.

## Key Context: Session 12 End State

- MMR 15m null-screen submitted at 2026-05-11T23:59:43 (backtest aa18298c, draw 1 of 8)
- Context ran out mid-null-screen; orchestrator process was killed; JVM backtest continued
- Backtest is still RUNNING (44% progress) when this session starts
- Need to wait for completion, then re-submit null-screen (new ik) — will begin from draw 2

Note: The orchestrator's null-screen is stateless (no checkpoint). When re-submitted, it runs all 8 draws fresh. Since the JVM only allows 1 concurrent backtest, the re-submission will queue behind the already-running aa18298c (which will complete naturally before the new null-screen draws are submitted, since the existing run isn't tied to any active orchestrator session).

## Current Archetype Status Table

| Archetype | Interval | Verdict | Journal |
|---|---|---|---|
| DCB | 4h | NOT_ROBUST + slippage fail | STRATEGY_OUTCOME |
| DCB | 1h | NO_EDGE | ANTI_PATTERN |
| DCB | 15m | NO_EDGE | ANTI_PATTERN 89886914 |
| DCB | 5m | INSUFFICIENT_DATA | JVM stuck - retry needed |
| MRO | 4h | NO_EDGE | ANTI_PATTERN |
| MRO | 1h | NO_EDGE | ANTI_PATTERN |
| MRO | 15m | NO_EDGE | ANTI_PATTERN |
| MRO | 5m | NOT TESTED | Next after DCB 5m |
| MMR | 4h | NO_EDGE | ANTI_PATTERN df6bfc9b |
| MMR | 1h | NO_EDGE | ANTI_PATTERN d1a58e1a |
| **MMR** | **15m** | **IN PROGRESS** | f90a8a64 (expect NO_EDGE) |
| MMR | 5m | NOT TESTED | After MMR 15m confirmed |
| TPB | 4h | DISCARDED (freq-starved) | ANTI_PATTERN 70e0f7b3 |
| TPB | 1h | DISCARDED (freq-starved) | ANTI_PATTERN |
| TPR | all | DISCARDED (freq-starved) | ANTI_PATTERN |
| FCARRY | 4h | NO_EDGE (confirmed post bug-fix) | ANTI_PATTERN 3c7ff079 |
| FCARRY | 1h | NO_EDGE (confirmed post bug-fix) | ANTI_PATTERN 17aa16b5 |

## Session Actions

### Step 1: Wait for backtest aa18298c to complete
The running MMR 15m backtest must complete before new null-screen draws can submit.

### Step 2: Re-submit MMR 15m null-screen
Use fresh idempotency key (nullscreen-mmr-15m-v3-YYYYMMDD). Same params:
- extremeAtrMult: float [1.5, 3.0]
- rsiOversoldMax: int [25, 40]
- stopAtrBuffer: float [0.5, 1.5]
- minRewardRiskRatio: float [1.5, 2.5]
n_draws=8, seed=42

### Step 3: Act on MMR 15m result

#### Branch A: EDGE_PRESENT (very unlikely — would be first MMR result with any signal)
- If max PF >= 1.0 for any draw: this is surprising, proceed to plan review
- Pre-register fresh hypothesis, request plan review, queue sweep

#### Branch B: NO_EDGE (expected — consistent with 1h and 4h results)
- Journal ANTI_PATTERN for MMR 15m
- Journal ARCHETYPE_EXHAUSTION for MMR (all intervals NO_EDGE on BTCUSDT)
- Proceed to DCB 5m retry

### Step 4: DCB 5m retry null-screen
Prior INSUFFICIENT_DATA was due to stuck backtest_run rows (JVM crash state).
After the FCARRY fix JVM restart, those stuck rows are cleared.
DCB 5m account_strategy: id=1727dfa0-d61b-4546-872f-24db0ed42f12

Test axes (4 dimensions):
- adxEntryMin: float [14, 22] — at 5m need lower ADX threshold for sufficient n
- stopAtrMult: float [1.5, 2.5] — wider stops for 5m noise
- tpR: float [1.5, 2.5] — lower target for 5m (more achievable)
- rvolMin: float [1.0, 1.3] — relative volume threshold

n_draws=8, seed=42

Expected: 300-1000 trades per draw at 5m. DCB mechanism (ADX+ATR breakout) may have better frequency at 5m.
Risk: 5m backtests are slow (large dataset: 28 months × 5m = ~240,000 bars per run).

### Step 5: If DCB 5m NO_EDGE, try MRO 5m and MMR 5m
After DCB 5m result:
- MRO 5m: Bollinger Band outer touch + RSI at intraday scale
- MMR 5m: EMA200 deviation at very short timescale

5m frequency note: at 5m intervals we get 2016 bars/week vs 42 at 4h. Mean-reversion strategies may work better at 5m (more events, but also more noise).

## Remaining Credible Paths on BTCUSDT

After completing the 5m interval tests for DCB/MRO/MMR, the remaining credible paths are:

### Path 1: ETHUSDT (BLOCKED - operator action required)
- ETHUSDT backfill needed: POST /api/v1/historical/jobs to Research JVM
- jobType: BACKFILL_FEATURE_STORE, symbol: ETHUSDT
- Opens the ETH instrument plane (separate regime from BTC)
- DCB may work on ETH (different trend characteristics)

### Path 2: New archetype (BLOCKED - operator action required)
- All existing spec archetypes exhausted on BTCUSDT
- Need operator to deploy new YAML + JVM restart

### Path 3: TPB at shorter intervals (LOW PRIORITY)
- TPB was freq-starved at 4h and 1h
- At 15m or 5m, trade frequency might be sufficient
- But the fundamental mechanism (momentum continuation after pattern) was untested

### Path 4: MRO 5m (IMMEDIATE)
- Only MRO interval not yet tested

## DATA_WISHLIST (Operator Action Required)

### Priority 1: ETHUSDT feature_store backfill (CRITICAL)
- **Why**: ETHUSDT market_data = 0 rows despite "Phase 3 shipped". Backfill job not yet run.
- **How**: POST /api/v1/historical/jobs to Research JVM with jobType=BACKFILL_FEATURE_STORE, symbol=ETHUSDT
- **Benefit**: Opens ETH instrument plane. DCB/MMR may work on ETH (different regime dynamics)
- **Time estimate**: 30-60 min

### Priority 2: New strategy archetype (MEDIUM)
- **Why**: May exhaust all existing spec archetypes on BTCUSDT before finding edge
- **How**: Operator deploys new YAML + JVM restart
- Candidates: VWAP-anchored mean reversion, RSI divergence, volume-based momentum

## Infra Lessons Carried Forward

1. Never submit concurrent null-screens. One at a time.
2. JVM has 1 concurrent backtest limit (429 if another is running).
3. JVM restart does NOT clear stuck backtest_run rows — need manual DB cleanup if RUNNING rows accumulate.
4. Context exhaustion terminates orchestrator null-screen service BUT JVM continues running the in-flight backtest.
5. After context loss during null-screen, check /api/v1/backtest for RUNNING rows before re-submitting.
6. SIGNIFICANT_EDGE parks fire on statistical_verdict, not decision_verdict — slippage gate must also pass.
7. TPE sampler resets RNG on each tick (Optuna study recreated from scratch) — use grid sweeps.
8. account_strategy creation requires: accountId (not strategyDefinitionId), strategyCode, symbol, intervalName (not interval), allowLong, allowShort, maxOpenPositions, capitalAllocationPct, priorityOrder.
