-- Phase 2c-followup — capture decision intent on backtest_trade rows the
-- same way Phase 2c added it to live trades. With these columns
-- TradeAttributionService can decompose realized P&L for backtest trades
-- into signal_alpha + execution_drift + sizing_residual, where
-- execution_drift is purely the simulated-slippage cost (since the
-- backtest is deterministic from intended_entry_price → actual_entry).
ALTER TABLE backtest_trade
    ADD COLUMN IF NOT EXISTS intended_entry_price NUMERIC(24, 12),
    ADD COLUMN IF NOT EXISTS intended_size        NUMERIC(24, 12),
    ADD COLUMN IF NOT EXISTS decision_time        TIMESTAMP;
