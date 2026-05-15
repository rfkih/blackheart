-- Phase B2 — per-strategy interval map for multi-timeframe backtests.
--
-- When set, e.g. {"LSR":"15m","VCB":"1h"}, the backtest coordinator loads
-- both candle streams and routes each strategy's executor only to its own
-- timeframe's bar closes. When null (legacy / single-timeframe runs), the
-- coordinator falls back to backtest_run.interval for all strategies — no
-- behavior change.
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS strategy_intervals JSONB;
