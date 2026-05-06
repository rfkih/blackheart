-- Phase 2c — capture decision-time intent on every Trade row so realized
-- P&L can be decomposed at exit into:
--   signal_alpha  = (actual_exit - intended_entry) × intended_size
--   exec_drift    = (intended_entry - actual_entry) × actual_size
--   sizing_resid  = (actual_size - intended_size) × (actual_exit - intended_entry)
--
-- Identity: realized_pnl = signal_alpha + exec_drift + sizing_resid
-- (verified algebraically — see TradeAttributionServiceTest).
--
-- All three columns are nullable: legacy trades pre-dating this migration
-- have no intent recorded, so attribution returns "unavailable" rather
-- than fabricating numbers.
ALTER TABLE trades
    ADD COLUMN IF NOT EXISTS intended_entry_price NUMERIC(24, 12),
    ADD COLUMN IF NOT EXISTS intended_size NUMERIC(24, 12),
    ADD COLUMN IF NOT EXISTS decision_time TIMESTAMP;
