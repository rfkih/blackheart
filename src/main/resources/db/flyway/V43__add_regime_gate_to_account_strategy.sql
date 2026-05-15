ALTER TABLE account_strategy
    ADD COLUMN IF NOT EXISTS regime_gate_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS allowed_trend_regimes       VARCHAR(100)          NULL,
    ADD COLUMN IF NOT EXISTS allowed_volatility_regimes  VARCHAR(100)          NULL;

COMMENT ON COLUMN account_strategy.regime_gate_enabled IS
    'When true, live entries are gated on the current FeatureStore regime matching allowed_trend_regimes / allowed_volatility_regimes.';
COMMENT ON COLUMN account_strategy.allowed_trend_regimes IS
    'Comma-separated trend_regime values permitted for entry (e.g. BULL,NEUTRAL). NULL means any regime passes.';
COMMENT ON COLUMN account_strategy.allowed_volatility_regimes IS
    'Comma-separated volatility_regime values permitted for entry (e.g. NORMAL,LOW). NULL means any regime passes.';
