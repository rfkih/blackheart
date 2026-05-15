# Research Plan 2026-05-13 (Session 15 — Second Stuck Backtest + Updated Status)

## Session Summary (Session 15)

Session 14 completed all journaling. Session 15 finds:
1. Operator cleared b8a4d2b5 (now COMPLETED) — thank you
2. A second MMR 15m backtest (81e1d670) immediately got stuck RUNNING at 01:12:36 on 2026-05-12
3. This appears to have been submitted by a retry attempt during the null-screen v2/v3/v4 attempts
4. User 8ff655fa is STILL at concurrency limit=1, all new submissions blocked
5. Journal entry 0e10a635 documents this new escalation

## OPERATOR ACTION REQUIRED (CRITICAL, BLOCKING)

### Action 1: Clear NEW stuck RUNNING backtest

```sql
UPDATE backtest_run SET status = 'FAILED'
WHERE backtest_run_id = '81e1d670-020f-4925-bceb-c2b73a2cec22'
AND status = 'RUNNING';
```

**WHY**: Second MMR 15m backtest orphaned after JVM restart. User 8ff655fa at limit=1 (in-flight=1). Blocks ALL new submissions.

**RECOMMENDATION**: After clearing, also run:
```sql
-- Optional: raise limit for research user to prevent future single-row blocks
-- (requires knowing the config table structure -- ask operator)
```
Or switch the orchestrator to use the research agent JWT (limit=3) instead of the static JWT.

### Action 2 (OPTIONAL but high-value): ETHUSDT backfill
POST to Research JVM (:8081):
```
POST http://localhost:8081/api/v1/historical/jobs
Content-Type: application/json
Authorization: Bearer <your-token>

{"jobType": "BACKFILL_FEATURE_STORE", "symbol": "ETHUSDT"}
```
Opens 4 new test dimensions for all archetypes.

### Action 3 (OPTIONAL, requires approval): Fix slippage proxy
Current `slippage_sensitivity()` in `analyze.py` uses `notional = max(|pnl|, 10) * 50`.
With initial_capital=100, this overestimates by ~100x.
Options:
- **A**: Use `notional_size` from `backtest_trade` table (best, requires schema check)
- **B**: Increase `initialCapital` from 100 to 10000 in `tick.py` line ~269

Both require user approval (V11 contract boundary).

## Archetype Status After Sessions 1-15

| Archetype | Interval | Verdict | Journal ID |
|---|---|---|---|
| DCB | 4h | DISCARD: 1.66%/yr, regime declining | f9e2a256 |
| DCB | 1h | NO_EDGE | ANTI_PATTERN |
| DCB | 15m | NO_EDGE | ANTI_PATTERN |
| DCB | 5m | NOT TESTED (BLOCKED by 81e1d670) | cf9a582a |
| MRO | 4h | NO_EDGE | ANTI_PATTERN |
| MRO | 1h | NO_EDGE | ANTI_PATTERN |
| MRO | 15m | NO_EDGE | ANTI_PATTERN |
| MRO | 5m | NOT TESTED (BLOCKED) | 7efe7e3f |
| MMR | 4h | NO_EDGE | ANTI_PATTERN df6bfc9b |
| MMR | 1h | NO_EDGE | ANTI_PATTERN d1a58e1a |
| MMR | 15m | NO_EDGE (6 draws: PF 0.289-0.498) | ANTI_PATTERN bea9b03c |
| MMR | 5m | NOT TESTED (BLOCKED) | 5829e344 |
| TPB | all | DISCARDED (freq-starved) | ANTI_PATTERN |
| FCARRY | all | EXHAUSTED | ANTI_PATTERN 20e92c4a |

## Next Steps After Operator Clears Stuck Backtest (81e1d670)

### Priority 1: DCB 5m null-screen
- Body pre-written: /c/tmp/dcb-5m-nullscreen.json (need to verify this file exists)
- IK: nullscreen-dcb-5m-v1-20260513
- Account strategy: 1727dfa0-d61b-4546-872f-24db0ed42f12
- Axes: adxEntryMin [14,22], stopAtrMult [1.5,2.5], tpR [1.5,2.5], rvolMin [1.0,1.3]
- Requires a pre-registered hypothesis first

### Priority 2: MRO 5m null-screen
- Body pre-written: /c/tmp/mro-5m-nullscreen.json
- Account strategy: ffc720c4-cc71-4c95-96e5-6f969aedc36d
- Axes: rsiOversoldMax, rsiOverboughtMin, stopAtrBuffer, minRewardRiskRatio

### Priority 3: MMR 5m null-screen
- Account strategy: c0436253-2240-4f34-a07e-d625eda2b196
- Axes: extremeAtrMult [1.5,3.0], rsiOversoldMax [25,40], stopAtrBuffer [0.5,1.5], minRewardRiskRatio [1.5,2.5]

### Priority 4 (after ETHUSDT backfill): ETHUSDT sweeps on all archetypes

## Infra Lessons (Updated Session 15)

1. JVM concurrency limit: user 8ff655fa = 1; research agent (99999999...) = 3
2. Static JWT maps to user 8ff655fa (regular user, not research agent)
3. Stuck RUNNING rows persist after JVM restart — must be manually cleared
4. slippage_haircut_pnl overestimates by ~100x at initial_capital=100
5. V11 +20bps slippage gate is a hard gate — cannot be bypassed without user approval
6. DCB 4h had SIGNIFICANT_EDGE but: annualized=1.66%/yr (far below 10%), declining regime — DISCARDED
7. MMR 15m confirmed NO_EDGE from 6 completed draws (PF max=0.498)
8. b8a4d2b5 was cleared by operator (now COMPLETED) but 81e1d670 immediately took its place

## Journal Chain (Sessions 1-15)

- bea9b03c: MMR 15m NO_EDGE (derived from 6 DB draws)
- 6ad0fdc4: MMR archetype exhaustion (4h/1h/15m all NO_EDGE)
- 3c84ee4c: ESCALATION b8a4d2b5 stuck backtest (RESOLVED by operator)
- 217dcd97: DCB 4h slippage analysis (superseded)
- f9e2a256: DCB 4h DISCARD corrected (1.66%/yr, regime declining)
- c787ed85: ARCHETYPE_EXHAUSTION_2026-05-12 checkpoint
- 0e10a635: ESCALATION 81e1d670 new stuck backtest (ACTIVE, needs clearing)
