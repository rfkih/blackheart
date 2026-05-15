# Phase B — Live-Gate Replay of Existing Backtest Trades

**Generated**: 2026-05-13 | **Account**: starsky / `76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2`

This is a diagnostic, not a fix. Each trade entry in the three selected backtest runs is replayed through the live executor's size + gate stack (mirrored in Python from `TradeOpenService` + `StrategyHelper`), using the account's **current** balance. It answers: of the trades these backtests said would happen, how many would the live system actually have opened today?

## Current account balance (snapshot used by the simulation)

| Asset | Balance |
|---|---|
| USDT | 38.75215094 |
| BTC | 0.00040000 |
| BNB | 0.02800942 |

All four active strategies are configured at **40% capital_allocation_pct** with `use_risk_based_sizing=false`. So:
- LONG live-sized notional = 38.75215094 × 0.40 = **15.5009 USDT** per entry
- SHORT live-sized qty = 0.00040000 × 0.40 = **0.00016000 BTC** per entry

Live exchange minimums (from `TradeConstant`): `MIN_POSITION_QTY=0.0001`, `MIN_USDT_NOTIONAL=7`, `QTY_STEP=0.00001`.

## Per-strategy pass rate

| Strategy | Run id | Trades | Live PASS | Live BLOCKED | Pass % |
|---|---|---:|---:|---:|---:|
| VBO 15m | `8818e563…` | 94 | 94 | 0 | 100.0% |
| VCB 1h | `0b8e022c…` | 47 | 47 | 0 | 100.0% |
| DCB 4h | `202709b1…` | 87 | 85 | 2 | 97.7% |

### Block reasons by strategy

**VBO 15m** (94 trades, PF 1.393739, backtest return 13.166600%)
  - `PASS`: 94

**VCB 1h** (47 trades, PF 2.027803, backtest return 12.991100%)
  - `PASS`: 47

**DCB 4h** (87 trades, PF 1.531240, backtest return 3.087000%)
  - `PASS`: 85
  - `notional 6.5603 < MIN_USDT_NOTIONAL 7`: 1
  - `notional 6.2122 < MIN_USDT_NOTIONAL 7`: 1

### LONG vs SHORT verdict breakdown

| Strategy | LONG_PASS | LONG_BLOCKED | SHORT_PASS | SHORT_BLOCKED |
|---|---:|---:|---:|---:|
| VBO 15m | 94 | 0 | 0 | 0 |
| VCB 1h | 47 | 0 | 0 | 0 |
| DCB 4h | 50 | 0 | 35 | 2 |

## Finding #7 cross-strategy overlap (`max_concurrent_trades=1` on the account)

Across the shared backtest period (per-strategy windows overlap roughly 2024-01 to 2026-05), 225 distinct hour-buckets contain at least one entry from these three strategies. Of those, **3 hour-buckets contain entries from 2+ strategies** — a lower bound on the rate at which live's account-level cap would have vetoed simultaneous entries the backtests admitted independently.

| Strategies wanting to enter in same hour | # of hours |
|---|---:|
| 1 | 222 |
| 2 | 3 |

This is a *crude* approximation (1-hour buckets, not trade-lifetime overlap, so the true overlap rate is higher). Implementation note: an exact analysis requires walking each strategy's open trades through time and counting concurrent positions per minute — out of scope here.

## Findings 5 / 6 — live size vs backtest size on PASS rows

For each trade the backtests admitted, here is the size the live system *would* have used at today's balance, vs the size the backtest actually used. The gap is the consequence of Finding 5 (lot/min-notional) and the documented backtest-cash-normalization vs live-asset-inventory SHORT sizing.

**VBO 15m** (94 PASS trades): avg backtest notional `8020.95 USDT` → avg live notional `15.09 USDT` (**0.19%** of backtest size).

**VCB 1h** (47 PASS trades): avg backtest notional `4414.11 USDT` → avg live notional `15.06 USDT` (**0.34%** of backtest size).

**DCB 4h** (85 PASS trades): avg backtest notional `5.14 USDT` → avg live notional `14.49 USDT` (**281.82%** of backtest size).

## Paper-trade diversion check

`paper_trade_run` table is **empty across the entire DB** for these 4 account_strategy rows. Because all four have `simulated=true`, any successful OPEN_LONG / OPEN_SHORT decision would have been written there instead of submitted to Binance. The empty table means either (a) the live JVM has not been up to evaluate these strategies on a closed bar, or (b) no strategy has produced an OPEN signal since the rows were activated. Worth separately confirming the live JVM's recent uptime.

## What this tells us

- **Whether each strategy *would* fire if a signal came right now**: answer depends purely on the gates above. Look at the LONG_PASS / SHORT_PASS columns — if both are 0 for a strategy at current balance, that strategy is effectively muted regardless of signal.
- **Whether the backtest result will hold up live**: every backtest PnL number is inflated by at least the % of rows live would BLOCK (column 'Live BLOCKED') *plus* the additional cap-collision rate from Finding 7.
- **The single biggest concrete fixable issue surfaced here** is probably Finding 5 — backtest needs to apply `floorToStep` + `MIN_*_NOTIONAL` validation so its trade list matches what live could actually have entered.
