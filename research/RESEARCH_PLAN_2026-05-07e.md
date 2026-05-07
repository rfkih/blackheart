# Research Plan 2026-05-07e — Archetype Exhaustion Confirmed; Operator Action Required

## Status
ARCHETYPE_EXHAUSTION — all available engine+asset+interval combinations have been null-screened or swept. No 4th profitable strategy found. Research is BLOCKED pending operator action.

## Session Actions Taken This Fire

1. **VCB ETHUSDT 1h null-screen** → NO_EDGE_DETECTED
   - PF=0.70-0.75 across all 8 random draws (n=119-133 per draw)
   - Adequate signal count but definitively negative PF
   - VCB on ETH generates enough trades but the edge is not there

2. **TPR BTCUSDT 1h null-screen** → NO_EDGE_DETECTED (confirmed with CORRECT param names)
   - Used adxEntryMin + longRsiMax + stopAtrMult (not the wrong rsiEntryMax params from prior session)
   - PF=0.38-0.98, n=3-21 per draw
   - Both signal-starved (max 21 trades over 17 months) AND losing
   - TPR archetype is definitively exhausted on all (BTC,ETH)×(15m,1h) combinations

3. **CMR BTCUSDT 1h null-screen** → INSUFFICIENT_DATA (all 3 backtests failed)
   - No BTCUSDT account_strategy exists for CMR
   - JVM returns FAILED status, cannot test

4. **DCB2 ETH 4h sweep** → FALSIFIED by portfolio gate (journaled in prior session)
   - Best cell: n=109, CI_lower=1.029, but Spearman corr with VBO = 0.70
   - Portfolio gate: 1.029 × (1 - 0.5 × 0.70) = 0.669 < 1.0 → DEMOTED
   - Structural reason: both DCB2 and VBO are breakout-on-expansion strategies

## Why Research Is Blocked

The portfolio gate formula `pf_lo_raw × (1 - 0.5 × |max_corr|) > 1.0` creates a structural barrier for breakout archetypes:
- Any breakout archetype will be correlated with VBO (Spearman ~0.7)
- Required raw CI_lower at corr=0.70 is 1.538 — unachievable at 17 months of data
- Mean-reversion archetypes are mechanistically uncorrelated with VBO but cannot be tested due to missing account_strategy rows for BTCUSDT

## Operator Actions Required (Priority Order)

### Priority 1: BBR BTCUSDT 1h (HIGHEST VALUE)
BBR (Bollinger Band Mean-Reversion) enters on extreme RSI while price touches BB bands, exits on mean-reversion. This is mechanistically ANTI-correlated with VBO:
- VBO fires when BB bands EXPAND after compression (volatility breakout)
- BBR fires when price is at BB extremes during MEAN-REVERSION conditions
- Expected Spearman correlation with VBO: near 0 or negative

Prior BBR testing at ETH 4h showed INSUFFICIENT_DATA (no account_strategy, not NO_EDGE). At 1h BTC, expected n = 4× that of 4h ETH = roughly 80-100 trades over 17 months.

If BBR BTC 1h achieves n>=100 and CI_lower>1.0 (which would require PF~1.5 given n=100), the portfolio gate would likely pass because low correlation means lower CI_lower threshold.

**SQL**: `INSERT INTO account_strategy (strategy_code, instrument, interval_name, simulated, enabled, created_time) VALUES ('BBR', 'BTCUSDT', '1h', true, false, now());`

### Priority 2: CMR BTCUSDT 1h
CMR (Chop Mean-Reversion) enters during low-ADX chop on RSI extremes. Also mechanistically uncorrelated with VBO. Risk: CMR on ETH 1h showed BULL-bleed structural failure (PF=0.50). BTC may behave differently.

**SQL**: `INSERT INTO account_strategy (strategy_code, instrument, interval_name, simulated, enabled, created_time) VALUES ('CMR', 'BTCUSDT', '1h', true, false, now());`

### Priority 3: DCB2 BTCUSDT 4h
DCB2 ETH 4h had real edge (CI_lower=1.029) but blocked by VBO corr=0.70. BTC DCB2 might have different correlation profile. **RISK**: VBO production strategy runs on BTC 15m — BTC breakout patterns may have HIGHER correlation with VBO than ETH (same asset), not lower. Test this last.

**SQL**: `INSERT INTO account_strategy (strategy_code, instrument, interval_name, simulated, enabled, created_time) VALUES ('DCB2', 'BTCUSDT', '4h', true, false, now());`

## What Next Session Should Do (Once BBR BTCUSDT 1h account_strategy exists)

1. Run null-screen: BBR BTCUSDT 1h, params: rsiOversoldMax [18-30], stopAtrBuffer [0.3-0.6], minRewardRiskRatio [1.5-2.5]
2. If EDGE_PRESENT: pre-register hypothesis, write plan, request plan review from quant-reviewer
3. Queue 18-cell 3D sweep (rsiOversoldMax × stopAtrBuffer × minRewardRiskRatio)
4. Run ticks until SIGNIFICANT_EDGE or sweep_exhausted
5. If SIGNIFICANT_EDGE: check portfolio_corr — if VBO corr < 0.5, proceed to graduation review + walk-forward
6. If VBO corr in [0.5, 0.7], need CI_lower > 1.333 to survive portfolio gate — still possible at n=100 if PF=1.6+
7. If VBO corr near 0: only need CI_lower > 1.0 — standard graduation requirements apply

## Data Wishlist (If Operator Actions Don't Unlock Path)

1. Funding rate history backfill for BTC+ETH (V34-V37 schema exists, table empty)
   - Unlocks FCARRY archetype on funding-carry alpha (orthogonal to price momentum)
   - ~1-2 developer days to backfill from Binance funding rate API

2. Longer backtest window (>3 years)
   - DCB2 ETH 1h had PF=1.13 but CI_lower=0.86 (n=294 insufficient)
   - At 3 years: n~530, CI_lower would be ~1.05-1.08 (borderline SIGNIFICANT_EDGE)
   - Requires data plumbing for pre-2024 OHLCV bars

3. Open interest historical data
   - Enables crowding/squeeze strategies (mechanistically orthogonal to VBO)

## Archetype Exhaustion Summary

| Engine | BTC | ETH | Blocker |
|--------|-----|-----|---------|
| LSR | Production (untouchable) | n=6 (BTC-specific) | Signal architecture |
| VCB | PF=1.14-1.20 (ceiling) | PF=0.70-0.75 (NO_EDGE) | Structural ceiling |
| VBO | Production (untouchable) | NO_EDGE 1h; n<20 4h | Trade-starved |
| DCB2 | No account_strategy | Portfolio gate (corr=0.70) | VBO correlation |
| MMR | PF=0.43 (NO_EDGE) | PF=0.53 (NO_EDGE) | BULL-bleed |
| BBR | **NEEDS account_strategy** | n<24 at 4h (INSUFFICIENT_DATA) | Missing BTC 1h a/s |
| CMR | **NEEDS account_strategy** | PF=0.50 (BULL-bleed) | Missing BTC 1h a/s |
| TPR | PF=0.50-0.73 (NO_EDGE) | n=13-18 (signal-starved) | Both blocked |
| FCARRY | PF=0.69 (bull-regime) | - | No funding-rate alpha |
| MTM | PF=0.55-0.65 | - | Negative |
| VSR | PF=0.13-0.23 | - | Catastrophic |

## Goal Status
NOT HIT — Blocked on account_strategy gaps. BBR BTCUSDT 1h is the highest-probability unlock.
