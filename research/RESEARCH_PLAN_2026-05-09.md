# RESEARCH_PLAN_2026-05-09 — DCB BTCUSDT 4h (Session 4 — DCB Primary Focus)

## Session Context

**Date**: 2026-05-09 (session 4 — continuation from context compaction)
**Goal**: Find 4th profitable strategy >= 10%/yr net of fees+slippage, walk-forward ROBUST.
**DB state**: strategy_definitions for FCARRY, LSR, TPR, VBO, VCB, TPB, DCB, MRO (all created).
**Infra**: Orchestrator healthy. DCB account_strategy bbb64ce7 (BTCUSDT 4h), MRO account_strategy 07d5810d (BTCUSDT 1h).

## Session Brief

1. **What's winning**: Zero SIGNIFICANT_EDGE in this DB cycle. DCB 4h exploratory shows promise.
2. **Discarded archetypes** (live journal evidence):
   - TPR BTCUSDT all intervals: DISCARDED (4h NO_EDGE PF=0.0, 15m NO_EDGE, 5m PF=0.46, 1h frequency-starved n=5-32)
   - FCARRY BTC 1h: NO_EDGE (null-screen bug: all draws identical PF=0.702 — param override not applied)
   - TPB BTCUSDT 1h: EDGE_PRESENT null-screen (P75=2.15) BUT n=5-32 per draw — DISCARDED (frequency-starved by requireBiasTimeframe=true)
   - MRO BTCUSDT 1h: DISCARDED (PF=0.165, win_rate=10.2% — BTC 1h is trending; mean-reversion anti-edge)
   - DCB2 ETH all intervals: VBO Spearman corr=0.70 (portfolio gate, prior session)
   - BBR BTC 1h: NO_EDGE (PF=0.13-0.23, prior session)
   - MMR BTC 1h: NO_EDGE (PF=0.53, prior session)
   - DCB2 BTC 1h: NO_EDGE (PF=0.53-0.75, prior session)
3. **ARCHETYPE_EXHAUSTION** journaled at 2026-05-09T15:51 (journal_id: 100968ba).
4. **Current primary**: DCB BTCUSDT 4h — hypothesis_id `dd43547e-862b-4751-88d7-4977da2fdb33`
   - strategy_definition: 31ad9b0b (archetype=donchian_breakout, SpecDrivenExecutorAdapter)
   - account_strategy: bbb64ce7 (BTCUSDT 4h, enabled=false, simulated=true)
   - Exploratory result: adxEntryMin=15, rvolMin=1.1, tpR=3.0, stopAtrMult=2.5 → 121 trades, PF=1.25, +2.18%
   - Null-screen RUNNING (background task btcaldz9f): axes adxEntryMin [12,25] x rvolMin [1.0,1.5] x tpR [1.5,3.5] x stopAtrMult [2.0,4.0]

## Constraints Reaffirmed

- Instruments: BTCUSDT and ETHUSDT only (Phase 3 plumbed end-to-end 2026-05-01)
- Intervals: 5m / 15m / 1h / 4h only
- Production strategies LSR/VCB/VBO untouchable
- Bar: >= 10%/yr net of fees+slippage; walk-forward ROBUST
- Research-mode: enabled=false, simulated=true
- No deploy-from-spec.sh (operator-only; not needed — strategy_definitions created via API)
- Reviewer approval mandatory before /queue and before /walk-forward

## Experiment 1: DCB BTCUSDT 4h (PRIMARY — NULL-SCREEN RUNNING)

**Hypothesis ID**: `dd43547e-862b-4751-88d7-4977da2fdb33` (ACTIVE, pre-registered 2026-05-09T16:05)
**Strategy Definition ID**: `31ad9b0b-7eb2-42a5-9d95-df8c63636b32` (archetype=donchian_breakout)
**Account Strategy ID**: `bbb64ce7-f167-4ded-9d4d-56b156373978` (BTCUSDT 4h)
**Mechanism**: Donchian-N channel breakout, ADX trend confirmation, RVOL surge confirmation,
break-even shift, single TP. requireBiasTimeframe=false — NO cross-TF frequency bottleneck.

**Exploratory evidence**: adxEntryMin=15, rvolMin=1.1, tpR=3.0, stopAtrMult=2.5 → 121 trades, PF=1.25, win_rate=41.3%, return=+2.18% — first archetype to clear n>=100 with PF>1.0.

**Null-screen** (4D: adxEntryMin x rvolMin x tpR x stopAtrMult, n_draws=8, seed=42):
- adxEntryMin: [12, 25] — trend quality gate
- rvolMin: [1.0, 1.5] — RVOL surge confirmation
- tpR: [1.5, 3.5] — reward-to-risk TP
- stopAtrMult: [2.0, 4.0] — stop loss ATR multiplier
**Status**: RUNNING (background task btcaldz9f, started ~2026-05-09T16:16)

**Decision branches**:
- EDGE_PRESENT (P75>=1.2, share>=0.25): proceed to plan review -> sweep
- INCONCLUSIVE: extend null-screen to n_draws=16 on same axes
- NO_EDGE_DETECTED: journal discard, pivot to DCB 15m or alternative archetype

**Anti-correlation**: DCB fires on fresh breakout EXPANSION; LSR fades extreme RSI; VBO fades ATR expansion. Different regimes. Expected Spearman corr < 0.40.

**Sweep design** (to execute after EDGE_PRESENT + plan review APPROVED):
- 4D grid: adxEntryMin=[15,20,25] x rvolMin=[1.0,1.2,1.4] x tpR=[2.0,2.5,3.0] x stopAtrMult=[2.0,2.5,3.0]
- 81 cells (3^4), V11 gates at each tick
- Interval: 4h, Instrument: BTCUSDT
- iter_budget: 5 per tick

## Experiment 2: DCB BTCUSDT 15m (BACKUP if 4h sweep fails)

**Rationale**: DCB 4h at loose params gives 121 trades; tighter params may cut below n>=100.
15m gives ~10x more bars — more entries, can apply tighter ADX/RVOL filters for quality.
**Pre-req**: Create new account_strategy for DCB BTCUSDT 15m (reuse strategy_definition 31ad9b0b).
**Null-screen dimensions**: adxEntryMin x rvolMin x tpR x stopAtrMult (same axes, different freq).

## Blocked archetypes

**TPB** (blocked): requireBiasTimeframe=true limits 1h to n<=32. Fix: operator edits TrendPullbackEngine.java.
**MRO** (discarded): BTC trending market makes mean-reversion anti-edge. Rescue via ETH blocked (no feature_store).
**FCARRY** (discarded): param bug — BacktestParamOverrideContext not read. Rescue via operator fix.

## Blockers Requiring Operator Action

1. **FCARRY param bug**: `FundingCarryStrategyService.java` lines 91-94. Fix: replace
   `strategyParamService.getActiveOverrides(accountStrategyId)` with
   `BacktestParamOverrideContext.isActive() ? BacktestParamOverrideContext.forStrategy(STRATEGY_CODE) : strategyParamService.getActiveOverrides(accountStrategyId)`
2. **ETH feature_store empty**: 0 rows for ETHUSDT. All ETH research blocked.
   Fix: trigger RECOMPUTE_FEATURE_STORE job for ETHUSDT 5m/15m/1h/4h.
3. **TPB frequency fix**: TrendPullbackEngine.java `requireBiasTimeframe` must be set to false
   OR bias filter must be redesigned so it counts entries from the native interval only.

## Blockers Requiring Operator Action

1. **FCARRY param bug**: `FundingCarryStrategyService.java` lines 91-94. Fix: replace
   `strategyParamService.getActiveOverrides(accountStrategyId)` with
   `BacktestParamOverrideContext.isActive() ? BacktestParamOverrideContext.forStrategy(STRATEGY_CODE) : strategyParamService.getActiveOverrides(accountStrategyId)`
2. **ETH feature_store empty**: 0 rows for ETHUSDT. All ETH research blocked.
   Fix: trigger RECOMPUTE_FEATURE_STORE job for ETHUSDT 5m/15m/1h/4h.

## Execution Order (session 4)

1. COMPLETED: TPR exhausted (5m/15m/1h/4h) — journaled ANTI_PATTERN + ARCHETYPE_EXHAUSTION
2. COMPLETED: TPB null-screen EDGE_PRESENT but frequency-starved (n=5-32) — journaled DISCARD
3. COMPLETED: MRO exploratory — 118 trades, PF=0.165 — journaled DISCARD (structural anti-edge)
4. COMPLETED: DCB strategy_definition (31ad9b0b) + account_strategy bbb64ce7 (BTCUSDT 4h) created
5. COMPLETED: DCB exploratory backtests — 121 trades, PF=1.25 at adxEntryMin=15, rvolMin=1.1, tpR=3.0
6. RUNNING: DCB 4h null-screen (background task btcaldz9f, 4 axes)
7. PENDING: If EDGE_PRESENT: POST /reviews/request -> reviewer -> APPROVED -> POST /queue
8. PENDING: POST /tick loop until SIGNIFICANT_EDGE or sweep_exhausted
9. On SIGNIFICANT_EDGE: POST /reviews/request (graduation) -> reviewer -> /walk-forward
10. ROBUST + >= 10%/yr: GOAL HIT

## V11 Success Criteria

- n >= 100 trades
- PF 95% CI lower > 1.0
- +20bps slippage net positive
- DSR >= 0.95 (cumulative-trial scaling, Tier 1)
- Walk-forward stability_verdict = ROBUST
- Annualized return >= 10% net of fees + 20bps slippage

---

*Written by quant-researcher 2026-05-09 (session 3 — continuation)*
