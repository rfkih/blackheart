# Fund Manager & Quant-Grade Enhancement Roadmap

Current state: **production-grade proprietary trading desk** — strong quant research
infrastructure (PSR, walk-forward, holdout, HLZ/DSR, Monte Carlo, Kelly sizing,
vol-targeting, correlation guard, slippage calibration) with real risk controls on
real capital.

Not yet: a fund management platform. Gaps are almost entirely in capital accounting,
compliance, and investor-facing reporting.

---

## Priority Stack (build order)

| # | Enhancement | Tier | Complexity | Blocking what |
|---|-------------|------|------------|---------------|
| 1 | NAV curve + daily reconciliation | Family office | Medium | Every fund metric downstream |
| 2 | Portfolio VaR / CVaR + notional cap | Family office | Medium | Risk table stakes for outside capital |
| 3 | HWM + performance fee engine | Family office | Low | Required before any external investor |
| 4 | PDF tearsheet generator | Family office | Medium | First ask from any allocator |
| 5 | FIFO/LIFO tax lot accounting | Registered | High | Legal in most jurisdictions |
| 6 | Liquidation / margin ratio warnings | Safety | Medium | Exists as a gap today |
| 7 | Parameter sensitivity harness | Quant depth | Medium | Research robustness |
| 8 | Factor attribution model | Institutional | High | Institutional due diligence |
| 9 | TWAP/VWAP execution | Institutional | High | Matters at scale |
| 10 | Regulatory reporting export | Compliance | High | Registration blocker |

---

## Detailed Specifications

### 1. NAV Curve + Daily Reconciliation

**What:** Time-weighted NAV series (daily mark-to-market, TWRR). Automated end-of-day
compare of expected vs actual P&L against the exchange.

**Why missing:** Balance is point-in-time from Binance. Silent errors (missed fills,
fee miscalculation) accumulate without daily reconciliation.

**Build notes:**
- New `nav_snapshot` table: `(snapshot_date, account_id, nav_usdt, twrr_inception, twrr_30d, twrr_90d, twrr_ytd)`
- Scheduled job: end-of-day sum of realized + unrealized P&L, compare vs Binance account balance
- Alert via `alert_event` if delta > configurable threshold (e.g. 0.1%)
- Expose via `GET /api/v1/portfolio/nav?accountId=&from=&to=`
- Frontend: replace point-in-time balance in HeroCard with NAV curve

---

### 2. Portfolio-Level VaR / CVaR + Notional Cap

**What:** Value at Risk (95%/99% confidence, 1-day horizon) and Expected Shortfall
(CVaR) aggregated across all open positions simultaneously. Hard notional cap.

**Why missing:** Per-strategy kill-switch exists but no aggregate risk number.
A regime shock can hit all strategies simultaneously.

**Build notes:**
- Historical simulation VaR: use last 252 days of daily portfolio P&L (already in equity curve)
- `PortfolioRiskService.computeVaR(accountId, confidenceLevel)` → returns VaR USDT + %
- CVaR = mean of losses beyond VaR threshold
- Hard notional cap: new `Account.maxPortfolioNotionalUsdt` column (V47 migration)
- `RiskGuardService.canOpen()` checks current open notional + proposed size ≤ cap
- Frontend: surface VaR in AtAGlance panel; red badge when >X% of NAV

---

### 3. High-Water Mark + Performance Fee Engine

**What:** Track the peak NAV for each account. Calculate accrued incentive fee
(e.g. 20% of profits above HWM). Reset HWM on redemption/crystallization.

**Why missing:** Required before any external investor allocates capital.

**Build notes:**
- New `high_water_mark` table: `(account_id, hwm_date, hwm_nav_usdt, crystallized_at)`
- `PerformanceFeeService.accrueIncentiveFee(accountId, currentNav)` → computes fee since last crystallization
- Fee rate and hurdle rate configurable per `Account` (new columns)
- New `fee_accrual` table for audit trail
- API: `GET /api/v1/portfolio/performance-fee?accountId=`

---

### 4. Investor-Grade Tearsheet (PDF)

**What:** One-page PDF with: equity curve, monthly returns heatmap, Sharpe/Sortino/PSR,
max drawdown chart, trade count, win rate, profit factor, top 5 / worst 5 trades.

**Why missing:** `AnalysisReport` JSON exists, UI shows metrics, but no exportable format.

**Build notes:**
- Backend: Jasper Reports or iText library → `TearsheetService.generate(backtestRunId)` → `byte[]`
- Alternatively: headless Playwright/Puppeteer rendering the existing analysis UI to PDF (simpler)
- `GET /api/v1/backtest/{runId}/tearsheet` → `Content-Type: application/pdf`
- Frontend: "Export PDF" button on backtest detail page
- Monthly returns table: already have trade timestamps and P&L — bucket by calendar month

---

### 5. FIFO/LIFO Tax Lot Accounting

**What:** Match each closing trade against the correct opening lot. Track cost basis,
realized gain/loss per lot, holding period (short vs long term).

**Why missing:** Realized P&L per trade exists but no lot-matching algorithm.
Required for tax filing in most jurisdictions.

**Build notes:**
- New `tax_lot` table: `(lot_id, account_id, symbol, open_trade_id, open_date, open_price, quantity, close_trade_id, close_date, close_price, realized_gain, holding_days, method)`
- `TaxLotService.match(trade, method: FIFO|LIFO|SPECIFIC_ID)` called on trade close
- CSV export: `GET /api/v1/trades/tax-export?accountId=&year=&method=FIFO`
- Frontend: "FIFO · tax" button on Journal page (already in UI, currently a stub)

---

### 6. Liquidation / Margin Ratio Warnings

**What:** Monitor how close each open position is to forced liquidation on Binance
Futures. Alert before the exchange closes the position automatically.

**Why missing:** Portfolio balance tracked but no liquidation threshold monitoring.
Kill-switch fires on drawdown but too late if price gaps through.

**Build notes:**
- Binance Futures API: `GET /fapi/v2/positionRisk` returns `liquidationPrice` per symbol
- New field `Trades.liquidationPrice` (BigDecimal, nullable) — synced on position refresh
- `LiquidationWatchdogService`: scheduled every 60s — if `markPrice / liquidationPrice < 1.15` (15% margin) → fire `alert_event` type `LIQUIDATION_WARNING`
- Frontend: red badge on position row when within 15% of liquidation price

---

### 7. Parameter Sensitivity / Stability Harness

**What:** For a given strategy + param set, measure how Sharpe changes when each
parameter is perturbed ±10%, ±25%. A robust strategy should degrade gracefully,
not cliff-edge at ±1 tick.

**Why missing:** Walk-forward tests time-robustness; no tool measures parameter-space
robustness. Over-fitted strategies can pass WF but still be brittle.

**Build notes:**
- `SensitivitySweepService.run(backtestRunId, perturbPct: [10, 25])`:
  - For each parameter in configSnapshot, run N=2 backtests at ±perturbPct
  - Aggregate: Sharpe stability score = `std(Sharpes) / mean(Sharpes)` (lower = more stable)
- New `sensitivity_result` table linked to `backtest_run_id`
- Research orchestrator: run sensitivity after every SIGNIFICANT_EDGE verdict before WF escalation
- Frontend: sensitivity heatmap on backtest detail (param × perturbation → Sharpe delta)

---

### 8. Factor Attribution Model

**What:** Decompose strategy returns into systematic factors: trend/momentum,
volatility regime, funding carry, and residual (true alpha).

**Why missing:** Trade-level attribution exists (signal_alpha, execution_drift,
sizing_residual on `Trades`) but no portfolio-level factor decomposition.

**Build notes:**
- Factors to track: BTC momentum (20d return), realized vol (20d), funding rate level, regime (BULL/BEAR/NEUTRAL)
- `FactorAttributionService.decompose(accountId, from, to)`:
  - OLS regression of daily P&L on daily factor returns
  - Returns: factor betas, R², unexplained alpha, t-stats
- New `factor_attribution_snapshot` table (daily, per account)
- Frontend: factor exposure bar chart on performance page

---

### 9. TWAP / VWAP Execution

**What:** Break large orders into slices over time (TWAP) or proportional to
observed volume (VWAP) to reduce market impact.

**Why missing:** All orders placed as market orders at bar close. Market impact
grows with notional size — not a problem at current scale but matters at $1M+.

**Build notes:**
- `TwapExecutionService.submit(tradeId, targetQty, durationMinutes, sliceCount)`:
  - Schedules N child orders at `duration / sliceCount` intervals
  - Each slice: `qty = targetQty / sliceCount`, market order via `BinanceClientService`
  - Tracks actual vs TWAP benchmark price
- New `TradeMode.TWAP` and `TradeMode.VWAP` values (already have `MULTI_SLICE`)
- Gate: only enabled for `notional > threshold` (e.g. $50k) to avoid over-engineering small trades

---

### 10. Regulatory Trade Reporting Export

**What:** Structured export of all executions in regulatory format — timestamps,
counterparty, price, quantity, fees, instrument, direction.

**Why missing:** Required for MiFID II (EU) or Form ADV / trade blotter (US) filings.
Audit trail exists but not in a structured regulatory format.

**Build notes:**
- `TradeReportService.exportMifid2(accountId, from, to)` → CSV/XML
  - Fields: execution_id, instrument_id (ISIN/CRYPTO), venue, timestamp, quantity, price, currency, direction, fee, counterparty
- `TradeReportService.exportBlotter(accountId, from, to)` → CSV (US style)
- `GET /api/v1/trades/regulatory-export?accountId=&format=MIFID2|BLOTTER&from=&to=`
- Frontend: export options on Journal page alongside existing CSV button

---

## Additional Gaps (lower priority)

### Operational
- **Disaster recovery / hot standby** — Two-JVM split documented; no active-active replication. Add PostgreSQL streaming replication + Redis Sentinel.
- **Exchange API rate-limit management** — No backoff/retry. Add `RateLimitInterceptor` in `BinanceClientService` with token-bucket algorithm.
- **Dead man's switch enhancement** — `LivenessWatchdogService` alerts but doesn't halt trading. Add auto-disable of all strategies if heartbeat missed > N minutes.

### Data Infrastructure
- **OHLCV gap detection** — `LivenessWatchdogService` detects stale ingest but no per-symbol gap check. Add `MarketDataQualityService.checkGaps(symbol, interval, from, to)`.
- **Open interest + long/short ratio** — Binance Futures provides these. Useful for regime detection and carry strategies. Add as columns to `FeatureStore`.
- **Tick data** — Storage-intensive. Consider a separate time-series DB (TimescaleDB or QuestDB) for tick capture if needed for microstructure research.

### Research
- **Regime-split performance reporting** — `BacktestAnalysisService` buckets by regime diagnostically. Surface as a separate table in the tearsheet: "Sharpe in BULL / BEAR / NEUTRAL regime."
- **Information ratio vs BTC benchmark** — Add `BenchmarkService` that tracks BTC TWRR alongside portfolio TWRR. IR = (portfolio_return - benchmark_return) / tracking_error.
- **Correlation-adjusted sizing** — Extend `BookVolTargetingService` to apply a portfolio-correlation haircut: `adjusted_size = base_size × sqrt((1 + (N-1) × avg_pairwise_corr) / N)`.

---

## Migration Roadmap

```
V46  — account_strategy.version (optimistic locking)       ← done
V47  — account.max_portfolio_notional_usdt
V48  — nav_snapshot table
V49  — high_water_mark + fee_accrual tables
V50  — tax_lot table
V51  — sensitivity_result table
V52  — factor_attribution_snapshot table
V53  — liquidation_price on trades
V54  — account.hurdle_rate_pct, incentive_fee_rate_pct
```
