ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS strategy_account_strategy_ids jsonb;
