-- Backfill strategy_promotion_log for pre-V15 active strategies.
--
-- WHY: V15 introduced the promotion-pipeline workflow but pre-existing live
-- strategies (LSR, VCB, VBO at the time of V15) were enabled directly via
-- UPDATE long before the audit table existed. Their currentState() returns
-- RESEARCH (the implicit default) which is wrong — they're actively trading
-- real capital. This backfill synthesizes the two-step
-- RESEARCH → PAPER_TRADE → PROMOTED history so the audit trail is consistent
-- with reality from this point forward.
--
-- WHAT IT DOES:
--   For every account_strategy row matching:
--     enabled = true AND is_deleted = false AND simulated = false
--   AND with NO existing strategy_promotion_log entries:
--     - Insert one RESEARCH → PAPER_TRADE row (created_time = NOW() - 1µs)
--     - Insert one PAPER_TRADE → PROMOTED row (created_time = NOW())
--   Both rows carry reviewer_user_id = NULL (system backfill, no human),
--   reason explaining this is a backfill, evidence={"backfill": true, ...}.
--
-- IDEMPOTENT: re-running this script does nothing if every active strategy
-- already has at least one promotion_log row. Safe to run multiple times.
--
-- USAGE:
--   1. Dry-run (preview which strategies would be backfilled):
--      psql -h <host> -U <user> -d trading_db -f V15_promotion_log_backfill.sql --set=APPLY=0
--
--   2. Apply (actually insert the backfill rows):
--      psql -h <host> -U <user> -d trading_db -f V15_promotion_log_backfill.sql --set=APPLY=1
--
--   3. Verify:
--      SELECT account_strategy_id, strategy_code, from_state, to_state, created_time
--      FROM strategy_promotion_log
--      WHERE evidence->>'backfill' = 'true'
--      ORDER BY created_time;
--
-- ROLLBACK: if you ever need to undo (unlikely but possible if the backfill
-- mis-targets a row), the rows are identifiable by evidence->>'backfill':
--      DELETE FROM strategy_promotion_log WHERE evidence->>'backfill' = 'true';
-- After delete, re-run with corrected predicates.

\set ON_ERROR_STOP on

-- Default APPLY=0 if caller didn't set it. Treat anything other than '1'
-- as a dry-run.
\if :{?APPLY}
\else
    \set APPLY 0
\endif

\echo '=== V15 promotion_log backfill ==='
\echo 'Mode:' :APPLY
\echo

BEGIN;

-- ── Show what would be backfilled ────────────────────────────────────────
\echo '── Active pre-V15 strategies needing backfill ──'

SELECT
    a.account_strategy_id,
    a.strategy_code,
    a.symbol,
    a.interval_name,
    a.created_time AS originally_created,
    EXISTS (
        SELECT 1 FROM strategy_promotion_log spl
        WHERE spl.account_strategy_id = a.account_strategy_id
    ) AS already_has_promotion_log
FROM account_strategy a
WHERE a.enabled = true
  AND a.is_deleted = false
  AND a.simulated = false
ORDER BY a.created_time;

-- ── Dry-run guard ────────────────────────────────────────────────────────
-- If APPLY != 1, ROLLBACK at the end. The two INSERTs below still run, but
-- the transaction will be rolled back so the DB is unchanged. The COUNT
-- query results are visible in the output so the operator can confirm
-- before re-running with APPLY=1.

-- ── Step 1: RESEARCH → PAPER_TRADE ───────────────────────────────────────
WITH inserted_paper AS (
    INSERT INTO strategy_promotion_log (
        promotion_id, account_strategy_id, strategy_code,
        from_state, to_state,
        reviewer_user_id, reason, evidence, created_time
    )
    SELECT
        gen_random_uuid(),
        a.account_strategy_id,
        a.strategy_code,
        'RESEARCH', 'PAPER_TRADE',
        NULL,
        'V15 backfill — strategy was active before promotion pipeline existed; ' ||
        'synthesizing RESEARCH→PAPER_TRADE→PROMOTED history for audit consistency',
        jsonb_build_object(
            'backfill', true,
            'phase', 'RESEARCH_TO_PAPER_TRADE',
            'reason_code', 'V15_PRE_EXISTING_LIVE_STRATEGY'
        ),
        NOW() - INTERVAL '1 microsecond'
    FROM account_strategy a
    WHERE a.enabled = true
      AND a.is_deleted = false
      AND a.simulated = false
      AND NOT EXISTS (
          SELECT 1 FROM strategy_promotion_log spl
          WHERE spl.account_strategy_id = a.account_strategy_id
      )
    RETURNING account_strategy_id, strategy_code
)
SELECT 'Inserted RESEARCH→PAPER_TRADE rows: ' || COUNT(*) AS step_1_summary
FROM inserted_paper;

-- ── Step 2: PAPER_TRADE → PROMOTED ───────────────────────────────────────
-- Idempotency guard: only insert PROMOTED if EXACTLY ONE promotion_log
-- row exists for this strategy (the RESEARCH→PAPER_TRADE row we just
-- created). If the count is 0, the WHERE NOT EXISTS in step 1 already
-- skipped this strategy. If the count is 2+, this strategy already has
-- a full backfill or genuine history — don't touch it.
WITH inserted_promoted AS (
    INSERT INTO strategy_promotion_log (
        promotion_id, account_strategy_id, strategy_code,
        from_state, to_state,
        reviewer_user_id, reason, evidence, created_time
    )
    SELECT
        gen_random_uuid(),
        a.account_strategy_id,
        a.strategy_code,
        'PAPER_TRADE', 'PROMOTED',
        NULL,
        'V15 backfill — strategy was active before promotion pipeline existed; ' ||
        'synthesizing RESEARCH→PAPER_TRADE→PROMOTED history for audit consistency',
        jsonb_build_object(
            'backfill', true,
            'phase', 'PAPER_TRADE_TO_PROMOTED',
            'reason_code', 'V15_PRE_EXISTING_LIVE_STRATEGY'
        ),
        NOW()
    FROM account_strategy a
    WHERE a.enabled = true
      AND a.is_deleted = false
      AND a.simulated = false
      AND (
          SELECT COUNT(*)
          FROM strategy_promotion_log spl
          WHERE spl.account_strategy_id = a.account_strategy_id
      ) = 1
    RETURNING account_strategy_id, strategy_code
)
SELECT 'Inserted PAPER_TRADE→PROMOTED rows: ' || COUNT(*) AS step_2_summary
FROM inserted_promoted;

-- ── Final verification ───────────────────────────────────────────────────
\echo
\echo '── Post-backfill state of all active strategies ──'

SELECT
    a.account_strategy_id,
    a.strategy_code,
    a.simulated,
    (
        SELECT spl.to_state
        FROM strategy_promotion_log spl
        WHERE spl.account_strategy_id = a.account_strategy_id
        ORDER BY spl.created_time DESC
        LIMIT 1
    ) AS current_state,
    (
        SELECT COUNT(*)
        FROM strategy_promotion_log spl
        WHERE spl.account_strategy_id = a.account_strategy_id
    ) AS log_row_count
FROM account_strategy a
WHERE a.enabled = true
  AND a.is_deleted = false
ORDER BY a.strategy_code;

-- ── Commit or rollback based on APPLY flag ───────────────────────────────
\if :APPLY
    COMMIT;
    \echo
    \echo '✓ Backfill APPLIED. strategy_promotion_log now reflects the'
    \echo '  implicit RESEARCH→PAPER_TRADE→PROMOTED history of every'
    \echo '  pre-V15 active strategy.'
\else
    ROLLBACK;
    \echo
    \echo '⚠  Dry-run only. No rows were committed. The COUNT lines above show'
    \echo '   what WOULD have been inserted. Re-run with --set=APPLY=1 to commit.'
\endif
