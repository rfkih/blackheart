# RESEARCH_PLAN_2026-05-09 — TPB BTCUSDT 1h (Spec-Driven Engine, Session 3)

## Session Context

**Date**: 2026-05-09 (session 3 — continuation from context compaction)
**Goal**: Find 4th profitable strategy >= 10%/yr net of fees+slippage, walk-forward ROBUST.
**DB state**: strategy_definitions created for FCARRY, LSR, TPR, VBO, VCB, TPB (just created).
**Infra**: Orchestrator healthy. TPB account_strategy created: 19cb0d7c (BTCUSDT 1h).

## Session Brief

1. **What's winning**: Zero SIGNIFICANT_EDGE in this DB cycle.
2. **Discarded archetypes** (live journal evidence):
   - TPR BTCUSDT 4h: NO_EDGE (PF=0.0, n=0-3)
   - TPR BTCUSDT 15m: NO_EDGE (PF=0.12-0.47, n=7-42)
   - TPR BTCUSDT 5m: 31 trades, PF=0.46 (frequency-starved by 4h bias filter)
   - TPR BTCUSDT 1h: EDGE_PRESENT null-screen but n=5-32 per draw (frequency-starved)
   - FCARRY BTC 1h: null-screen invalid (param bug — all 8 draws used default params)
   - DCB2 ETH all intervals: VBO Spearman corr=0.70 (portfolio gate, prior session)
   - BBR BTC 1h: NO_EDGE (PF=0.13-0.23, prior session)
   - MMR BTC 1h: NO_EDGE (PF=0.53, prior session)
   - DCB2 BTC 1h: NO_EDGE (PF=0.53-0.75, prior session)
3. **ARCHETYPE_EXHAUSTION** journaled at 2026-05-09T15:51 (journal_id: 100968ba).
4. **Current action**: TPB null-screen running (background task bwqk5qr0p)
   - strategy_definition: c63b603d (archetype=trend_pullback, SpecDrivenExecutorAdapter)
   - account_strategy: 19cb0d7c (BTCUSDT 1h, enabled=false, simulated=true)
   - Axes: adxEntryMin x biasAdxMin x biasAdxMax x tp1R (4D, n_draws=8, seed=42)
   - KEY CHANGE vs TPR: SpecDrivenExecutorAdapter.execute() correctly uses BacktestParamOverrideContext
5. **Standing hypothesis**: TPB BTCUSDT 1h — hypothesis_id `628218a6-c5aa-4f32-bb96-0793f6507547`

## Constraints Reaffirmed

- Instruments: BTCUSDT and ETHUSDT only (Phase 3 plumbed end-to-end 2026-05-01)
- Intervals: 5m / 15m / 1h / 4h only
- Production strategies LSR/VCB/VBO untouchable
- Bar: >= 10%/yr net of fees+slippage; walk-forward ROBUST
- Research-mode: enabled=false, simulated=true
- No deploy-from-spec.sh (operator-only; not needed — strategy_definitions created via API)
- Reviewer approval mandatory before /queue and before /walk-forward

## Experiment 1: TPB BTCUSDT 1h (PRIMARY — NOW RUNNING)

**Hypothesis ID**: `628218a6-c5aa-4f32-bb96-0793f6507547` (ACTIVE, pre-registered 2026-05-09T13:50)
**Strategy Definition ID**: `c63b603d-0775-4a54-9532-a4b5cc81cd5b` (archetype=trend_pullback)
**Account Strategy ID**: `19cb0d7c-5cba-4a10-817d-c95406f73bb4` (BTCUSDT 1h)
**Mechanism**: EMA50>EMA200 trend stack, pullback to EMA20 within pullbackTouchAtr*ATR,
reclaim candle with body+CLV+volume quality filters, composite score>=0.55.
Two-leg exit: TP1 break-even shift, runner ATR-phase trail. 4h bias filter on 1h entries.

**Critical fix vs TPR**: SpecDrivenExecutorAdapter.execute() correctly reads
BacktestParamOverrideContext.forStrategy() at line 66, so sweep params ARE honored.
TPR had frequency starvation because the 4h bias filter with ADX 25-40 was too narrow.
This null-screen includes biasAdxMin/biasAdxMax in sweep ranges to widen the filter.

**Null-screen** (4D: adxEntryMin x biasAdxMin x biasAdxMax x tp1R, n_draws=8, seed=42):
- adxEntryMin: [20, 35] — trend quality gate
- biasAdxMin: [10, 20] — lower 4h ADX bias bound (widened from default 25)
- biasAdxMax: [50, 70] — upper 4h ADX bias bound (widened from default 40)
- tp1R: [1.5, 3.0] — reward-to-risk first TP leg
**Status**: RUNNING (background task bwqk5qr0p, started ~2026-05-09T22:54)

**Decision branches**:
- EDGE_PRESENT (P75>=1.2, share>=0.25 AND n>=20/draw): proceed to plan review -> sweep
- INSUFFICIENT_DATA (n<20 per draw): pivot to TPB BTCUSDT 4h
- NO_EDGE_DETECTED: journal discard, pivot to Experiment 2 (DCB2 BTC 4h)

**Anti-correlation**: TPB fires on PULLBACK to EMA20; VBO fires on breakout expansion.
Structurally opposite entry conditions. Expected Spearman corr with VBO < 0.30.

**Sweep design** (if null-screen EDGE_PRESENT, n>=20/draw):
- Grid or TPE: adxEntryMin x biasAdxMin x biasAdxMax x tp1R (4 axes)
- Values: adxEntryMin=[20,25,30] x biasAdxMin=[10,15,20] x biasAdxMax=[50,60,70] x tp1R=[1.5,2.0,3.0] = 81 cells
- Interval: 1h, Instrument: BTCUSDT
- iter_budget: 5 (V11 gates applied at each tick)

## Experiment 2: DCB2 BTCUSDT 4h (BACKUP if TPB 1h fails)

**Rationale**: DCB2 BTC 1h was NO_EDGE. BTC 4h is genuinely different — fewer but
higher-quality breakout signals, less noise. Prior DISCARD on 1h ≠ 4h.
**Pre-req**: Create strategy_definition for DCB2 (archetype=donchian_breakout) and
account_strategy for BTCUSDT 4h via API.
**Null-screen dimensions**: adxMaxForChop x breakoutN x tp1R (3D)

## Experiment 3: FCARRY BTCUSDT 1h (TERTIARY — blocked by param bug)

**BLOCKER**: FundingCarryStrategyService.java line 91-94 uses strategyParamService.getActiveOverrides()
instead of BacktestParamOverrideContext.forStrategy(). Null-screen invalid. Fix requires
operator to edit Java source and redeploy research JVM.
**Action when unblocked**: POST /null-screen with entryZ=[0.5,1.5], entryZExit=[0.5,2.5],
positionSizePct=[0.5,5.0] (3D, n_draws=8).

## Blockers Requiring Operator Action

1. **FCARRY param bug**: `FundingCarryStrategyService.java` lines 91-94. Fix: replace
   `strategyParamService.getActiveOverrides(accountStrategyId)` with
   `BacktestParamOverrideContext.isActive() ? BacktestParamOverrideContext.forStrategy(STRATEGY_CODE) : strategyParamService.getActiveOverrides(accountStrategyId)`
2. **ETH feature_store empty**: 0 rows for ETHUSDT. All ETH research blocked.
   Fix: trigger RECOMPUTE_FEATURE_STORE job for ETHUSDT 5m/15m/1h/4h.

## Execution Order (current session)

1. COMPLETED: TPR exhausted (5m/15m/1h/4h) — journaled ANTI_PATTERN + ARCHETYPE_EXHAUSTION
2. COMPLETED: TPB strategy_definition created (c63b603d) + account_strategy (19cb0d7c)
3. RUNNING: POST /null-screen for TPB BTCUSDT 1h (background task bwqk5qr0p)
4. PENDING: Based on null-screen result:
   a. EDGE_PRESENT: POST /reviews/request -> reviewer -> APPROVED -> POST /queue
   b. INSUFFICIENT_DATA (n<20): pivot to TPB BTCUSDT 4h null-screen
   c. NO_EDGE_DETECTED: pivot to DCB2 BTC 4h (create strategy_definition, run null-screen)
5. PENDING: POST /tick loop until SIGNIFICANT_EDGE or sweep_exhausted
6. On SIGNIFICANT_EDGE: POST /reviews/request (graduation) -> reviewer -> /walk-forward
7. ROBUST + >= 10%/yr: GOAL HIT

## V11 Success Criteria

- n >= 100 trades
- PF 95% CI lower > 1.0
- +20bps slippage net positive
- DSR >= 0.95 (cumulative-trial scaling, Tier 1)
- Walk-forward stability_verdict = ROBUST
- Annualized return >= 10% net of fees + 20bps slippage

---

*Written by quant-researcher 2026-05-09 (session 3 — continuation)*
