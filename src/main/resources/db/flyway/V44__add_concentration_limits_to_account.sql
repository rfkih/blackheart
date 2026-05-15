ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS max_corr_block_threshold       NUMERIC(5, 4) NULL,
    ADD COLUMN IF NOT EXISTS max_capital_concentration_pct  NUMERIC(5, 2) NULL;

COMMENT ON COLUMN accounts.max_corr_block_threshold IS
    'Pearson correlation threshold (0.0–1.0). When a new entry strategy has 30-day daily P&L correlation >= this value with any other same-side open strategy, the entry is blocked. NULL disables the check.';
COMMENT ON COLUMN accounts.max_capital_concentration_pct IS
    'Max sum of capital_allocation_pct allowed across same-direction open strategies. E.g. 80 blocks a new LONG when existing LONGs already occupy 75%+ of allocation. NULL disables the check.';
