-- Total per-account concurrent-trade cap.
--
-- Null = no total cap (only per-direction maxConcurrentLongs/Shorts apply).
-- When set, the live orchestrator rejects new entries before any
-- strategy-specific check fires. Bounds: [0, 20] enforced at request time.
--
-- This complements the existing direction-specific caps; it does NOT
-- replace them. A user wanting "max 3 trades total but no more than
-- 2 longs at once" sets both layers.
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS max_concurrent_trades INTEGER;
