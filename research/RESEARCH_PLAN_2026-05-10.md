# Session 9 Research Plan — 2026-05-10
Strategy: 4th profitable strategy (>=10pct/yr net fees+20bps slippage, ROBUST walk-forward)

## State on Entry

### Outcomes From Session 8 (2026-05-09)
- MMR UNBLOCKED: strategy_definition a046eedf, account_strategy aa7dac0a (1h), 6e74c1ec (4h)
- MMR 1h null-screen: NO_EDGE_DETECTED (PF=0.46-0.59, all draws below 1.0) — ANTI_PATTERN d1a58e1a
- MRO 15m null-screen: NO_EDGE_DETECTED (PF=0.23-0.33) — ANTI_PATTERN c16546df
- DCB 15m null-screen: NO_EDGE_DETECTED (PF=0.52-0.68) — ANTI_PATTERN 89886914

### Cumulative Discard Table
| Archetype/Interval | Verdict | Reason |
|---|---|---|
| DCB 4h BTCUSDT | NOT_ROBUST WF | Regime collapse 2025-2026; 2024 trend bias |
| DCB 1h BTCUSDT | NO_EDGE | Null-screen max PF=0.88 |
| DCB 15m BTCUSDT | NO_EDGE | Null-screen max PF=0.68 |
| MRO 1h BTCUSDT | NO_EDGE | Null-screen PF<0.40 |
| MRO 4h BTCUSDT | NO_EDGE | Null-screen max PF=1.25, n=34 insufficient |
| MRO 15m BTCUSDT | NO_EDGE | Null-screen PF=0.23-0.33 |
| MMR 1h BTCUSDT | NO_EDGE | Null-screen PF=0.46-0.59 |
| TPB all intervals | DISCARD | Frequency-starved (n=2-32; requireBiasTimeframe=true) |
| TPR all intervals | DISCARD | Frequency-starved (n=5-32 at any interval) |
| FCARRY | BLOCKED | Java param bug (operator fix needed) |

### Remaining Untested Combinations (BTCUSDT)
1. **MMR 4h** — account_strategy 6e74c1ec exists; null-screen not run (highest priority)
2. **MMR 5m** — high frequency, may have sufficient n but likely noisy
3. **TPB 4h** — native 4h, no cross-TF conflict (still BLOCKED: no strategy_definition after DB reset)
4. **DCB ETHUSDT** — BLOCKED (ETH feature_store empty)

## Hypothesis Pre-Registration: MMR 4h BTCUSDT

**Hypothesis:** At 4h intervals, MomentumMeanReversionEngine fires on larger ATR displacements from EMA200. These are meaningful regime reversals rather than intraday noise. BTC's 4h structure produces ~15-40 ATR-extreme events/year that revert to EMA50 — enough for n>=80 over the 28-month backtest window. The 4h interval's larger average move per bar implies better RR, which may offset the lower frequency. The choppy 2025-2026 BTC market (flat EMA200) should increase mean-reversion frequency relative to 2024 trending regime. Dual-directional (long below EMA200, short above) provides regime neutrality.

**Mechanism:** close deviates >extremeAtrMult*ATR from EMA200 AND RSI<rsiOversoldMax (long) or RSI>rsiOverboughtMin (short). Exit target: EMA50. Stop: close +/- stopAtrBuffer*ATR. Minimum RR gate: minRewardRiskRatio.

**Parameter axes for null-screen:**
- extremeAtrMult: [1.5, 3.0] (controls entry selectivity — lower = more entries, higher = fewer but more extreme)
- rsiOversoldMax: [25, 40] (RSI gate — wider = more entries)
- stopAtrBuffer: [0.5, 1.5] (stop width)
- minRewardRiskRatio: [1.5, 2.5] (RR gate)

**Expected outcome:** EDGE_PRESENT or INCONCLUSIVE. Mechanism: 4h bars capture significant deviation events. If 1h (n=564-1015 per draw) showed PF<0.60, 4h (n=~30-150 per draw) may differ because: (1) larger absolute moves per deviation event, (2) EMA200 more stable at 4h = cleaner anchor, (3) less noise pollution.

**Counter-argument (falsification):** If the mechanism itself (fade EMA200 deviation) has no edge regardless of timeframe, 4h will also fail. 1h null-screen PF=0.46-0.59 suggests the mechanism may be inherently weak on BTCUSDT.

**Account strategy ID for 4h:** 6e74c1ec-a124-4e56-83c5-73131baf39c8

## Experiment 1: MMR 4h Null-Screen (8 draws)

**Pre-screen approach:**  
POST /null-screen with n_draws=8, seed=42  
Param ranges: extremeAtrMult=[1.5,3.0], rsiOversoldMax=[25,40], stopAtrBuffer=[0.5,1.5], minRewardRiskRatio=[1.5,2.5]  
Account strategy: 6e74c1ec

**Decision branches:**
- EDGE_PRESENT (P75>=1.2 OR share_PF_ge_1.2>=0.25): Request plan review immediately, queue focused 4D grid sweep
- INCONCLUSIVE: Queue small 8-cell confirmatory grid (extremeAtrMult x rsiOversoldMax x stopAtrBuffer x minRewardRiskRatio)
- NO_EDGE_DETECTED: Journal ANTI_PATTERN for MMR 4h, pivot to Experiment 2

## Experiment 2 (fallback if MMR 4h fails): DCB ETHUSDT 4h null-screen

BLOCKED by ETH feature_store empty. If operator backfills ETH, this is the immediate next step.

## Experiment 3 (fallback): MMR 5m BTCUSDT null-screen

MMR at 5m would generate very high n (thousands of draws per year). Risk: 5m is noisier, slippage impacts larger, and the EMA200 signal is less meaningful at 5m granularity. PF likely to be sub-1.0. Low priority.

## Experiment 4 (fallback): TPB 4h BTCUSDT null-screen

BLOCKED: requires strategy_definition deployment (operator-only). If operator deploys TPB 4h spec: immediate null-screen with axes adxEntryMin, stopAtrBuffer, tp1R.

## If all remaining untested combos yield NO_EDGE:

Journal ARCHETYPE_EXHAUSTION_2026-05-10 with DATA_WISHLIST:
1. ETH backfill (enables DCB/MRO/MMR/TPB on ETHUSDT)
2. FCARRY Java fix (enables funding-rate carry)
3. TPB 4h deploy (operator deploys spec)
4. Consider new spec archetype: VBO-derivative with volume-profile entry gate

## Constraints Reaffirmed
- BTC/ETH only, intervals 5m/15m/1h/4h only
- Production strategies LSR/VCB/VBO untouchable
- 10%/yr net after fees+slippage mandatory bar
- Research-mode only (no promote, no deploy)
- Null-screen before full sweep (conserve DSR budget)
- Pre-register hypothesis before any sweep

## Decision Criteria for Next Session
- MMR 4h EDGE_PRESENT: proceed to plan review + grid sweep
- MMR 4h NO_EDGE: journal exhaustion update, await operator action
- If operator has backfilled ETH by next session: immediately run null-screens on DCB/MRO/MMR ETHUSDT 4h
