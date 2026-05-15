# Research Plan 2026-05-11 (Session 12 — FCARRY First Valid Test)

## Session Premise (5-line brief)

**What's currently winning**: No 4th strategy yet. Production LSR/VCB/VBO untouched (+20%/yr each).
**What's discarded**: DCB (all BTCUSDT intervals — 4h NOT_ROBUST WF + slippage fail, 1h/15m/5m NO_EDGE/INSUFFICIENT_DATA); MRO (all intervals NO_EDGE); MMR (4h, 1h NO_EDGE); TPB (frequency-starved all intervals).
**What's unblocked**: FCARRY Java param override bug fixed (operator). BacktestParamOverrideContext.forStrategy("FCARRY") now layered in resolveOverrides(). Research JVM restarted.
**Standing hypothesis**: FCARRY 4h (8f5d2dbb) — first valid null-screen with correct param overrides.
**Goal**: ≥10%/yr net of fees+20bps slippage, walk-forward ROBUST.

## Key Context: FCARRY Bug History

Prior FCARRY null-screen (1h, session 11) returned `NO_EDGE_DETECTED` but was INVALID:
- All 8 draws: identical PF=0.702, n_trades=65 (params not varying at all)
- Root cause: `FundingCarryStrategyService.execute()` called `strategyParamService.getActiveOverrides()` instead of `BacktestParamOverrideContext.forStrategy()`
- Fix shipped: `resolveOverrides()` now reads `BacktestParamOverrideContext.forStrategy(STRATEGY_CODE)` first, layers stored params beneath (mirrors VBO pattern)
- Journal: `4ba5cf3e` documents the bug; `1eaf55cb` is the INVALID null-screen (marked ANTI_PATTERN)

**This session runs the first VALID FCARRY null-screen.**

## Hypothesis

**ID**: `8f5d2dbb-9b98-4586-bf1e-14fe95c8dd4e`  
**Strategy**: FCARRY BTCUSDT 4h  
**Mechanism**: Funding-rate z-score fade. Short when z > +entryZ (longs overcrowded, paying premium). Long when z < -entryZ (shorts overcrowded). Exit when |z| < exitZ (carry edge collapsed) or holdMaxBars time-stop or ATR stop.  
**Why 4h first**: Funding rates refresh every 8h on Binance. 4h bars capture the full funding cycle dynamics. 5m/15m would generate false signals between funding events.  
**Why regime-neutral**: Funding positive ~75% of BTC hours historically. The edge is structural (overcrowded-side reversion) not directional (BTC price prediction).

## Null-Screen Design (Step 1)

**Axes** (4 dimensions, satisfies ≥3 constraint):
- `entryZ`: float [1.0, 3.0] — entry selectivity. Lower = more trades, higher = selectivity
- `exitZ`: float [0.2, 0.8] — exit when carry collapses. Higher = earlier exit
- `holdMaxBars`: int [6, 24] — time stop (6 bars at 4h = 24h; 24 bars = 96h / 4 days)
- `atrStopMult`: float [1.0, 3.0] — ATR stop width

**n_draws**: 8, seed=42  
**Expected PF if fix works**: varied distribution, some draws > 1.0  
**Expected n_trades**: 20-80 per draw at 4h (low frequency, regime-neutral)  
**Slippage note**: Low trade frequency means slippage drag is modest. Even at 20bps per trade, 40 trades/year × 20bps = 0.8% drag vs. expected 10-20% gross return.

## Decision Tree After Null-Screen

### Branch A: EDGE_PRESENT (PF distribution varied, max PF ≥ 1.2, share(PF≥1.0) ≥ 50%)
1. Pre-register HYPOTHESIS for sweep (already done: 8f5d2dbb)
2. Request plan review from quant-reviewer
3. Queue sweep with entryZ, exitZ, holdMaxBars, atrStopMult as axes
4. Add minAbsRate8h as 5th dimension if budget allows
5. Run ticks until SIGNIFICANT_EDGE or exhaustion

### Branch B: NO_EDGE (PF distribution flat, max PF < 1.0) — IMPLIES FIX NOT WORKING
- If all draws still identical PF=0.702: bug not fixed. Journal ESCALATION, stop.
- If draws vary but PF < 0.90 uniformly: mechanism has no edge on BTCUSDT 4h. Try 1h next.

### Branch C: NO_EDGE (varied PF but all < 1.0, fix IS working)
- Fix is working but 4h has no edge
- Try FCARRY 1h (null-screen single request)
- If 1h also NO_EDGE: journal ARCHETYPE_EXHAUSTION for FCARRY

### Branch D: INSUFFICIENT_DATA (JVM issue)
- Check JVM health, retry with idempotency key rotation

## Archetype Status Table (Updated)

| Archetype | Interval | Verdict | Notes |
|---|---|---|---|
| DCB | 4h | NOT_ROBUST + slippage fail | STRATEGY_OUTCOME |
| DCB | 1h | NO_EDGE | ANTI_PATTERN |
| DCB | 15m | NO_EDGE | ANTI_PATTERN 89886914 |
| DCB | 5m | INSUFFICIENT_DATA | JVM stuck - backtest_run rows RUNNING |
| MRO | 4h | NO_EDGE | ANTI_PATTERN |
| MRO | 1h | NO_EDGE | ANTI_PATTERN |
| MRO | 15m | NO_EDGE | ANTI_PATTERN |
| MMR | 4h | NO_EDGE | ANTI_PATTERN df6bfc9b |
| MMR | 1h | NO_EDGE | ANTI_PATTERN |
| TPB | 4h | DISCARDED (freq-starved) | ANTI_PATTERN 70e0f7b3 |
| TPB | 1h | DISCARDED (freq-starved) | ANTI_PATTERN |
| TPR | all | DISCARDED (freq-starved) | ANTI_PATTERN |
| FCARRY | 1h | INVALID null-screen (bug) | 1eaf55cb - do not count |
| **FCARRY** | **4h** | **IN PROGRESS** | **8f5d2dbb - first valid test** |

## Why FCARRY Is the Highest-Conviction Remaining Path

1. **Regime-neutral mechanism**: Funding carry works when longs are overcrowded (pay funding). BTC longs pay ~75% of hours historically. The edge is structural, not dependent on BTC price direction.
2. **Different risk factor**: DCB/MRO/MMR/TPB all bet on price direction. FCARRY bets on crowding dynamics in the derivatives market. Independent factor.
3. **Low frequency, low slippage**: At 4h with entryZ=2.0, expect 20-60 trades/year. DCB failed slippage because 114 trades × 20bps = 2.3% annual drag. FCARRY at 40 trades × 20bps = 0.8% drag on expected 10-15% gross.
4. **Confirmed data pipeline**: V35 funding rate backfill shipped; feature_store has fundingRateZ, fundingRate8h, fundingRate7dAvg for BTCUSDT.
5. **Bug is fixed**: The one blocker (param overrides not honored) is now resolved with confirmed code + JVM restart.

## Session Results (Updated)

### FCARRY Bug Fix Confirmed
- The Java param override fix was confirmed working: null-screens show varied PF and n_trades (not all identical like the buggy version)
- Fix location: `FundingCarryStrategyService.java` line 340, `resolveOverrides()` method

### FCARRY 4h Null-Screen — NO_EDGE (3c7ff079)
- Standard axes (entryZ/exitZ/holdMaxBars/atrStopMult): mean=0.780, max=0.991, P75=0.836
- Share(PF>=1.0) = 0.0 — ALL draws lose money
- Best result: holdMaxBars=6 (shortest hold) → PF=0.991

### FCARRY 1h Null-Screen — NO_EDGE (17aa16b5)
- Same axes at 1h: mean=0.713, max=0.818, P95=0.790
- Worse than 4h — more frequent trades at 1h don't help
- Share(PF>=1.0) = 0.0

### FCARRY Extreme-Carry Axis — NO_EDGE + FREQUENCY_ZERO
- minAbsRate8h=0.00015-0.0005: max PF=0.845, n_trades=9-27 (too low)
- minAbsRate8h=0.001-0.005: n_trades=0 — BTCUSDT never hits these extreme funding rates

### Conclusion: FCARRY Fully Exhausted
All BTCUSDT archetypes confirmed dead. ARCHETYPE_EXHAUSTION logged (e55326ec).
Next up: MMR 15m (loop rule: least-recently-tested) — IN PROGRESS

## Archetype Status Table (FINAL)

| Archetype | Interval | Verdict | Journal |
|---|---|---|---|
| DCB | 4h | NOT_ROBUST + slippage fail | STRATEGY_OUTCOME |
| DCB | 1h | NO_EDGE | ANTI_PATTERN |
| DCB | 15m | NO_EDGE | ANTI_PATTERN 89886914 |
| DCB | 5m | INSUFFICIENT_DATA | JVM stuck |
| MRO | 4h | NO_EDGE | ANTI_PATTERN |
| MRO | 1h | NO_EDGE | ANTI_PATTERN |
| MRO | 15m | NO_EDGE | ANTI_PATTERN |
| MMR | 4h | NO_EDGE | ANTI_PATTERN df6bfc9b |
| MMR | 1h | NO_EDGE | ANTI_PATTERN |
| **MMR** | **15m** | **IN PROGRESS** | f90a8a64 |
| TPB | 4h | DISCARDED (freq-starved) | ANTI_PATTERN 70e0f7b3 |
| TPB | 1h | DISCARDED (freq-starved) | ANTI_PATTERN |
| TPR | all | DISCARDED (freq-starved) | ANTI_PATTERN |
| FCARRY | 4h | NO_EDGE (confirmed post bug-fix) | ANTI_PATTERN 3c7ff079 |
| FCARRY | 1h | NO_EDGE (confirmed post bug-fix) | ANTI_PATTERN 17aa16b5 |

## DATA_WISHLIST (Operator Action Required)

### Priority 1: ETHUSDT feature_store backfill (CRITICAL)
- **Why**: ETHUSDT market_data = 0 rows despite "Phase 3 shipped". Backfill job not yet run.
- **How**: POST /api/v1/historical/jobs to Research JVM with jobType=BACKFILL_FEATURE_STORE, symbol=ETHUSDT
- **Benefit**: Opens ETH instrument plane. DCB/MMR may work on ETH (different regime dynamics)
- **Time estimate**: 30-60 min

### Priority 2: New strategy archetype (MEDIUM)
- **Why**: All existing spec archetypes exhausted on BTCUSDT
- **How**: Operator deploys new YAML + JVM restart

## Infra Lessons Carried Forward

1. Never submit concurrent null-screens. One at a time. 5m especially heavy.
2. JVM restart does NOT clear stuck backtest_run rows — need manual DB cleanup if RUNNING rows accumulate.
3. SIGNIFICANT_EDGE parks fire on statistical_verdict, not decision_verdict — slippage gate must also pass.
4. TPE sampler resets RNG on each tick (Optuna study recreated from scratch) — use grid sweeps.
5. account_strategy creation requires: accountId (not strategyDefinitionId), strategyCode, symbol, intervalName (not interval), allowLong, allowShort, maxOpenPositions, capitalAllocationPct, priorityOrder. Missing any @NotNull field returns 500.
6. FCARRY account_strategy was deleted between sessions — always verify before null-screen submission.
