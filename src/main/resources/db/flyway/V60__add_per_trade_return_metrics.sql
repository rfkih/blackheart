-- V60 — Per-trade return metrics on backtest_run.
--
-- Why:
--   Headline `return_pct` is (net_profit / initial_capital) — capital-based.
--   With risk-based sizing a strategy that places small notionals can show a
--   tiny capital return despite a strong per-trade edge, masking real signal.
--   The new columns expose two sizing-independent views:
--
--     avg_trade_return_pct
--       Simple mean of per-trade return rate (pnl / notional × 100). Answers
--       "what's the average edge per trade". Independent of how much equity
--       was risked.
--
--     geometric_return_pct_at_alloc_90
--       Compounded equity multiplier minus 1, in percent, assuming every
--       trade was sized at 90% of equity. Answers "what would this strategy
--       have produced if it were sized aggressively against equity each
--       time". Walks trades chronologically; clamps to ruin if any step
--       would drive equity to ≤ 0.
--
--   Both written by BacktestMetricsService.buildSummary; null on legacy rows.

ALTER TABLE backtest_run
    ADD COLUMN avg_trade_return_pct NUMERIC(14, 6),
    ADD COLUMN geometric_return_pct_at_alloc_90 NUMERIC(28, 6);

COMMENT ON COLUMN backtest_run.avg_trade_return_pct IS
    'Mean per-trade return rate (pnl / notional) in percent. Sizing-independent.';

-- Width NUMERIC(28,6) — 22 integer digits — defends against compounding
-- explosions on pathological / overfit sweep candidates. A 1000-trade
-- backtest with +5%/trade compounds at 90% sizing to ~10^19 multiplier
-- (10^21 %); narrower precisions can overflow saveAndFlush mid-run and
-- leave a backtest_run stuck after the trades have already been written.
COMMENT ON COLUMN backtest_run.geometric_return_pct_at_alloc_90 IS
    'Compounded equity multiplier - 1, in percent, assuming every trade sized at 90 percent of equity. Order-sensitive; clamps to ruin when a step would zero equity.';
