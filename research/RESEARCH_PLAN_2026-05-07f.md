# Research Plan 2026-05-07f — BBR BTCUSDT 1h: Null-Screen + 27-Cell Sweep

## Hypothesis

**hypothesis_id:** `10238a68-5b72-495a-86d2-03b1cab8af41`
**hypothesis_code:** bbr-btc-1h-01
**Journal entry:** `10238a68-5b72-495a-86d2-03b1cab8af41`

BBR (BollingerBandReversionEngine) on BTCUSDT 1h enters when RSI is in oversold territory AND price touches the lower Bollinger Band, then exits on mean-reversion toward the midband. The core thesis is **mechanistic anti-correlation with VBO**: VBO fires on volatility expansion (BB bands widening), while BBR fires during mean-reversion conditions after extreme RSI readings — structurally opposite market conditions. Expected Spearman correlation with VBO: 0 to -0.3.

## Context / Prior State

- **account_strategy confirmed**: `deae0a9c-1412-49f1-9e5d-a7cd5b9be6c8` (BBR BTCUSDT 1h, enabled=false, simulated=true, created 2026-04-28).
- **Prior BBR null-screen failures** (7912da2f / 73bfb5a4 / a9042c7e) were ALL on ETHUSDT — no account_strategy existed for ETH. These are irrelevant to this BTC run.
- All breakout archetypes (DCB2, VBO 4h) are blocked by portfolio gate corr >= 0.70. Mean-reversion (BBR) is the only credible path.
- Current leaderboard top is DCB2 ETH 4h at CI_lower=1.029 but that was demoted by portfolio gate (corr=0.70 with VBO).

## Plan of Attack

### Step 1: Null-Screen (this fire)
POST /null-screen with fresh seed=99 and BTCUSDT (not ETHUSDT).

**Params:**
- `rsiOversoldMax`: float [18, 30] — controls how oversold RSI must be at entry (tighter = rarer but higher conviction)
- `stopAtrBuffer`: float [0.3, 0.6] — ATR multiplier for initial stop (risk management)
- `minRewardRiskRatio`: float [1.5, 2.5] — minimum R:R before entry allowed

**Expected outcome:** 8 draws, seed=99. Given 17 months of BTC 1h bars (~12,400 bars), RSI extremes should occur ~1-2% of bars = 120-250 potential entry conditions. With additional filters, expect n=60-150 per draw.

**Decision gates:**
- EDGE_PRESENT (P95_PF >= 1.2 OR share_pf_ge_1.2 >= 0.1): proceed to plan review and 27-cell sweep
- INCONCLUSIVE: run with wider params (rsiOversoldMax up to 35)
- NO_EDGE_DETECTED: journal BBR BTCUSDT as failed, pivot to CMR BTCUSDT 1h (needs operator to create account_strategy)
- INSUFFICIENT_DATA (3+ consecutive failures): investigate JVM/data-plane failure mode

### Step 2: Plan Review
POST /reviews/request {target_kind: "plan", plan: {strategy_code: "BBR", axis_names: ["rsiOversoldMax", "stopAtrBuffer", "minRewardRiskRatio"], hypothesis_id: "10238a68-5b72-495a-86d2-03b1cab8af41"}}

Spawn quant-reviewer subagent, await APPROVED/CONDITIONAL_APPROVAL verdict.

### Step 3: 27-Cell Grid Sweep (if review approved)
POST /queue with:
- strategy_code: BBR
- interval_name: 1h
- instrument: BTCUSDT
- sweep_config (grid):
  - rsiOversoldMax: [20, 24, 28]
  - stopAtrBuffer: [0.3, 0.45, 0.6]
  - minRewardRiskRatio: [1.5, 2.0, 2.5]
- iter_budget: 27
- hypothesis_id: 10238a68-5b72-495a-86d2-03b1cab8af41

**Fresh idempotency key** (NOT reusing prior keys). Key: `bbr-btc-sweep-20260507f-001`

### Step 4: Tick Until Result
POST /tick repeatedly (background) until:
- SIGNIFICANT_EDGE: check portfolio_corr, proceed to graduation review if max_corr < 0.5
- sweep_exhausted: journal outcome, pivot
- NO_EDGE: discard BBR BTC 1h archetype

## Stat-Sig Requirements (V11 gates)
- n >= 100 trades
- PF 95% CI_lower > 1.0
- +20bps slippage still positive
- PSR >= 0.95 / DSR >= 0.95 (with cumulative-trial scaling)
- Walk-forward ROBUST

## Portfolio Gate Analysis
| VBO Correlation | Required CI_lower | Achievable at n=100 if PF=... |
|---|---|---|
| 0.0 | 1.000 | 1.05+ (easy) |
| 0.3 | 1.176 | 1.25+ (moderate) |
| 0.5 | 1.333 | 1.40+ (harder) |
| 0.7 | 1.538 | 1.60+ (very hard) |

BBR is expected in the 0.0-0.3 corr range — structural anti-correlation argument is strong.

## Fallback Plan (if BBR BTCUSDT fails)
If BBR BTCUSDT null-screen → NO_EDGE or INSUFFICIENT_DATA:
1. Journal DATA_WISHLIST entry: CMR BTCUSDT 1h needs account_strategy (operator action)
2. Journal DATA_WISHLIST entry: Funding rate backfill for FCARRY archetype
3. Continue with ARCHETYPE_EXHAUSTION pattern until operator creates CMR or funding data arrives

## Hard Constraints
- No live promotion, no JVM restart, no spec deployment
- LSR/VCB/VBO are untouchable
- BBR runs research-mode only (enabled=false, simulated=true)
- Never pass override_review_gate=true
