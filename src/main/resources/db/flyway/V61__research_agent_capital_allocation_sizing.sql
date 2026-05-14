-- V61 — Flip research-agent account_strategy rows to capital-allocation sizing.
--
-- Why:
--   Risk-based sizing produces tiny realized USDT returns when a candidate's
--   pnl_per_trade is small (notional ≈ risk_pct × stop_dist^-1 × cash, often
--   < 5% of cash). That obscures the per-trade edge in the headline
--   `return_pct` and misrepresents the candidate's potential at an aggressive
--   sizing. The research pipeline's job is to find edge, not to constrain it
--   to a conservative-bet risk budget — that decision is made AT promotion,
--   not during sweep evaluation.
--
--   V60 added the sizing-independent metric (avg_trade_return_pct +
--   geometric_return_pct_at_alloc_90) so the gate keeps a clean view of edge.
--   This migration ALSO normalises the research-agent's own runs to
--   capital-allocation sizing so the realized backtest cash flow reflects
--   the aggressive view by default.
--
-- Scope:
--   Touches ONLY the dedicated research-agent account
--   (account_id = '99999999-9999-9999-9999-000000000002', see V54). Admin's
--   live-trading rows — including the protected LSR/VCB/VBO — are untouched.
--   The hard-list rule "Don't touch protected production strategies" stays
--   intact: their account_id is different.
--
-- Net effect on research rows:
--   use_risk_based_sizing  TRUE/FALSE  → FALSE   (legacy capital-alloc path)
--   capital_allocation_pct  *          → 90.00   (90% per trade)
--
-- Idempotent: re-run is a no-op (rows already satisfy the predicate).

UPDATE account_strategy
SET use_risk_based_sizing  = FALSE,
    capital_allocation_pct = 90.0000,
    version                = version + 1,
    updated_time           = NOW(),
    updated_by             = 'flyway:V61'
WHERE account_id = '99999999-9999-9999-9999-000000000002'
  AND is_deleted = FALSE
  AND (use_risk_based_sizing = TRUE OR capital_allocation_pct <> 90.0000);
