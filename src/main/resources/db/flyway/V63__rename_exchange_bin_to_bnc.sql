-- V63 — Rename exchange code BIN → BNC.
--
-- Why:
--   The live executor branches on account.exchange == "BNC"
--   (LiveTradingDecisionExecutorService:328, :373) but historical rows and
--   the V54 research-agent seed used "BIN", so every live OPEN_LONG /
--   OPEN_SHORT fell through to the "Unsupported exchange" warn and never
--   placed a real Binance order. Operator confirmed BNC is the canonical
--   code; BIN was wrong everywhere it appeared (DB rows, DTO regex,
--   frontend dropdowns). Futures support is dropped — only spot today.
--
-- Scope:
--   In-place UPDATE of accounts.exchange. Column is VARCHAR(3), no CHECK
--   constraint exists, BNC fits. Idempotent: rerun is a no-op once all
--   rows are BNC.
UPDATE accounts
SET exchange = 'BNC'
WHERE exchange = 'BIN';
