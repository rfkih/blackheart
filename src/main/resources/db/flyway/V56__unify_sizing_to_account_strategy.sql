-- V56 — Unify sizing config to account_strategy.
--
-- Why:
--   V55 added account_strategy.use_risk_based_sizing + risk_pct, but only
--   the legacy strategies (LSR/LSR_V2/VCB/VBO/FCARRY) read them. Spec engines
--   (DCB / MMR / MRO / TPR / RAHT_V1 / TSMOM_V1 / generated archetypes)
--   continued to read useRiskBasedSizing/riskPct/riskPerTradePct from spec
--   body params with their own defaults (TRUE / 0.02). Two sources of truth
--   confuses operators and meant the V55 toggle was inert for ~half the
--   platform.
--
-- This migration:
--   Backfills existing spec-engine account_strategy rows so they preserve
--   their prior behavior after the engine code is rerouted through the
--   unified StrategyHelper.calculateLongEntryNotional / calculateShortEntryQty
--   helpers (which read account_strategy fields). Without this backfill,
--   the V55 default of FALSE/0.0500 would silently flip every existing
--   spec-engine strategy from risk-based-2% to direct-allocation-5% on
--   the first trade after deploy.
--
-- Legacy strategies keep their V55 defaults (FALSE/0.0500) so live trading
-- parity holds on protected production strategies — the operator must
-- explicitly opt them into risk-based sizing via the UI toggle. That
-- preserves the rollout safety we committed to in V55.
--
-- Rollout safety:
--   Restricts the backfill to rows still at V55 defaults
--   (use_risk_based_sizing = FALSE AND risk_pct = 0.0500). If an operator
--   explicitly toggled a spec-engine strategy off (or set a custom risk_pct)
--   between V55 and V56 deploys, that change is preserved — V56 only
--   touches rows that look untouched since the V55 column-add.

UPDATE account_strategy
SET use_risk_based_sizing = TRUE,
    risk_pct = 0.0200
WHERE strategy_code NOT IN ('LSR', 'LSR_V2', 'VCB', 'VBO', 'FCARRY')
  AND use_risk_based_sizing = FALSE
  AND risk_pct = 0.0500;
