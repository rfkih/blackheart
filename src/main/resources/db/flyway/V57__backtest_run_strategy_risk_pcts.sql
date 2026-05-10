-- V57 — Per-strategy risk-pct override map for backtest runs.
--
-- Why:
--   V56 unified sizing config to account_strategy (use_risk_based_sizing +
--   risk_pct + capital_allocation_pct). The backtest wizard already exposes
--   a per-strategy capital_allocation_pct override via
--   backtest_run.strategy_allocations (JSONB map of code → pct). Operators
--   running comparison sweeps now want the parallel override for risk_pct
--   so a single backtest can ask "what does LSR look like at 3% risk per
--   trade vs the live row's 2%?" without persistently flipping the
--   account_strategy and re-running.
--
-- Wire format:
--   {"LSR": 0.0300, "VCB": 0.0200} — fractional, same scale as
--   account_strategy.risk_pct (0 < x ≤ 0.20). Null map / missing key →
--   fall back to the persisted account_strategy.risk_pct, exactly like
--   strategy_allocations falls back to capital_allocation_pct.
--
-- The existing risk_per_trade_pct column on backtest_run stays as a
-- diagnostic scalar (passed through to RiskSnapshot.baseRiskPct) — it
-- continues to be expressed in PERCENT scale (e.g. 2.0) per its existing
-- consumers (research-orchestrator), distinct from this map's fractional
-- scale.

ALTER TABLE backtest_run
    ADD COLUMN strategy_risk_pcts JSONB;

COMMENT ON COLUMN backtest_run.strategy_risk_pcts IS
    'Per-strategy risk_pct override for this run. Map of strategy_code → '
    'fractional risk per trade (0 < x <= 0.20). Null or missing key falls '
    'back to account_strategy.risk_pct. V57+.';
