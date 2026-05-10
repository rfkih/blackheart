# Session 8 Research Plan — 2026-05-10
Strategy: 4th profitable strategy (>=10pct/yr net fees+20bps slippage, ROBUST walk-forward)

## State on Entry (from journal + queue read)
### Previously Tested (No Edge / Blocked)
- DCB 4h: SIGNIFICANT_EDGE but WF NOT_ROBUST + slippage=-110. Dead.
- DCB 1h: NO_EDGE (null-screen max PF=0.88)
- MRO 1h: NO_EDGE (raw PF=0.13-0.35)
- MRO 4h: NO_EDGE (max PF=1.25 n=34 insufficient)
- TPR: ALL intervals frequency-starved (4h bias filter). DISCARDED.
- TPB 1h: EDGE_PRESENT null-screen but frequency-starved (n=2-32). DISCARDED.
- FCARRY: param bug (still blocked, operator action needed)

### Major Session 8 Development: MMR UNBLOCKED
MomentumMeanReversionEngine (archetype=momentum_mean_reversion) was BLOCKED in all prior sessions.
This session: Created strategy_definition (a046eedf) + account_strategy rows (aa7dac0a for 1h, 6e74c1ec for 4h).
MMR is now testable.

## Running Null-Screens (all submitted this session)
1. DCB 15m: task bb1cae00w (4 draws, seed=42). Hypothesis: ffc87901.
2. MRO 15m: task b33xphf5c (4 draws, seed=42). Hypothesis: 9191487e.
3. MMR 1h:  task b8xblmobk (4 draws, seed=99). Hypothesis: 584bba80.

## Decision Branches (by priority)

### MMR 1h (highest priority — new, unblocked, dual-directional)
EDGE_PRESENT (P75>=1.2, share>=0.25):
  - Request plan review with this plan
  - Queue sweep: extremeAtrMult x rsiOversoldMax x stopAtrBuffer x minRewardRiskRatio
  - Focused grid: [1.5,2.0,2.5] x [28,32] x [0.5,1.0] x [1.5,2.0] = 24 cells
  - iter_budget=24, early_stop=true, require_walk_forward=true

NO_EDGE:
  - Journal ANTI_PATTERN for MMR 1h
  - Try MMR 4h null-screen (4 draws) — 4h may have better RR for this mechanism
  
### DCB 15m (if EDGE_PRESENT)
  - Request plan review
  - Queue: adxEntryMin x rvolMin x tpR x stopAtrMult (4D grid)
  - Sweep: [18,20,22,24] x [1.0,1.2,1.4] x [1.5,2.0,2.5] x [1.5,2.0] = 72 cells
  - Focus on adxEntryMin=20-24 range (DCB 4h winner zone)
  - Risk: 15m noisier than 4h; slippage will be larger issue at 15m

### MRO 15m (if EDGE_PRESENT)
  - Request plan review
  - Queue: rsiOversoldMax x rsiOverboughtMin x stopAtrBuffer x minRewardRiskRatio (4D)
  
### If ALL null-screens NO_EDGE
  - Journal updated archetype exhaustion
  - Remaining blockers: FCARRY (param bug), ETH backfill
  - DATA_WISHLIST reminder: 
    1. Fix FundingCarryStrategyService.java line 94 (param override bug)
    2. Backfill ETHUSDT feature_store (0 rows currently)
  - Continue with MMR 4h null-screen (still untested)

## Strategy Logic
MMR is structurally the best remaining candidate because:
1. EMA200-anchored ATR entry (different from all prior tested engines)
2. No cross-TF bias filter (unlike TPR/TPB which were frequency-starved)
3. Dual-directional (long+short): suits both trending and range-bound regimes
4. 2025-2026 choppy BTC aligns with EMA200 mean-reversion dynamics
5. EMA50 target = longer hold than MRO (EMA20) but shorter than DCB (distant TP)

