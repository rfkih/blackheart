-- Probabilistic Sharpe Ratio per backtest_run.
--
-- PSR(0) — probability that the true Sharpe exceeds zero given the observed
-- per-period Sharpe, sample size, skewness, and kurtosis. Penalises Sharpes
-- that come from short samples or fat-tailed/skewed return distributions.
-- Stored as a [0, 1] decimal, never NULL once a run COMPLETED (legacy rows
-- pre-dating this migration stay null).
ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS psr NUMERIC(10, 6);
