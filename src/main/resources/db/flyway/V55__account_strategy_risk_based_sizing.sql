-- V55 — Per-strategy risk-based position sizing toggle for legacy strategies.
--
-- Why:
--   Until now `account_strategy.capital_allocation_pct` had two different
--   meanings depending on the engine. Spec engines (DonchianBreakout,
--   MomentumMeanReversion, MeanReversionOscillator, TrendPullback) treat it
--   as a NOTIONAL CAP and size off `riskPct` from spec params (default 2%).
--   Legacy strategies (LSR, VCB, VBO, FundingCarry) treat it as the DIRECT
--   trade size — 50% allocation = 50 USDT trade on a 100 USDT account.
--
--   This migration unifies the option-set: legacy strategies can now opt
--   into the same risk-based sizing model via a per-strategy toggle, with
--   the user-supplied `risk_pct` being the per-trade risk knob and
--   `capital_allocation_pct` becoming the notional cap on the resulting
--   position size.
--
-- Rollout safety:
--   `use_risk_based_sizing` defaults to FALSE on existing rows so live
--   trading behavior is unchanged until the operator explicitly flips a
--   strategy via the new edit panel. `risk_pct` backfills to 5% but is
--   dormant while the toggle is OFF.

ALTER TABLE account_strategy
    ADD COLUMN use_risk_based_sizing BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN risk_pct NUMERIC(5, 4) NOT NULL DEFAULT 0.0500;

ALTER TABLE account_strategy
    ADD CONSTRAINT chk_account_strategy_risk_pct
        CHECK (risk_pct > 0 AND risk_pct <= 0.20);

COMMENT ON COLUMN account_strategy.use_risk_based_sizing IS
    'When TRUE, LONG entries on legacy strategies (LSR/VCB/VBO/FundingCarry) '
    'use StrategyHelper.calculateRiskBasedNotional. capital_allocation_pct '
    'becomes the notional cap; risk_pct is the per-trade risk fraction.';

COMMENT ON COLUMN account_strategy.risk_pct IS
    'Per-trade risk as a fraction of cash balance (0 < x <= 0.20). Used only '
    'when use_risk_based_sizing = TRUE. Default 5%.';
