# Backfill scripts

One-time data backfills tied to a Flyway migration that restructures business
state. Each script is **idempotent** — re-running has no effect after the
first successful application. Each script supports a **dry-run mode** so you
can preview the changes before committing.

These are NOT Flyway migrations themselves — they are operator-confirmed
data fixups intentionally kept outside the auto-apply path. A schema
migration runs unattended at boot; a data backfill needs eyes on it.

## Available scripts

### `V15_promotion_log_backfill.sql`

**Purpose:** for every `account_strategy` row that is currently `enabled=true`
and has zero entries in `strategy_promotion_log` (i.e. live BEFORE V15
existed), synthesize the implicit `RESEARCH → PAPER_TRADE → PROMOTED`
history so the audit trail reflects reality.

**When to run:** once, **after V15 applies cleanly to your prod DB**. If you
have only `LSR`, `VCB`, `VBO` enabled, this script will insert 6 rows
(2 per strategy) and exit.

**Order of operations on a fresh deploy:**

1. Deploy new JAR → service restarts → Flyway applies V14 (DB roles) and V15 (promotion-pipeline schema). The new `account_strategy.simulated` column appears, defaulted to FALSE on every existing row. Live trading continues unchanged.
2. Operator dry-runs this backfill. Output shows the 3 live strategies, all flagged `already_has_promotion_log = false`.
3. Operator applies the backfill. `strategy_promotion_log` gains 6 rows.
4. From now on, every state change goes through `POST /api/v1/strategy-promotion/{id}/promote`.

**Dry-run** (preview, no writes):
```bash
psql -h localhost -U postgres -d trading_db \
  -f deploy/backfill/V15_promotion_log_backfill.sql \
  --set=APPLY=0
```

The `--set=APPLY=0` (default if omitted) wraps the inserts in a transaction
that always ROLLBACKs at the end. The `INSERT ... RETURNING COUNT(*)` lines
in the output tell you exactly how many rows would have been written.

**Apply** (commit the inserts):
```bash
psql -h localhost -U postgres -d trading_db \
  -f deploy/backfill/V15_promotion_log_backfill.sql \
  --set=APPLY=1
```

The transaction COMMITs; rows persist.

**Verify after apply:**
```sql
-- Every active strategy should show current_state = PROMOTED, log_row_count = 2
SELECT a.strategy_code, a.simulated,
       (SELECT spl.to_state FROM strategy_promotion_log spl
        WHERE spl.account_strategy_id = a.account_strategy_id
        ORDER BY spl.created_time DESC LIMIT 1) AS current_state,
       (SELECT COUNT(*) FROM strategy_promotion_log spl
        WHERE spl.account_strategy_id = a.account_strategy_id) AS log_count
FROM account_strategy a
WHERE a.enabled = true AND a.is_deleted = false;

-- Expected output (with three live strategies):
--   strategy_code | simulated | current_state | log_count
--  ---------------+-----------+---------------+-----------
--   LSR           | f         | PROMOTED      | 2
--   VBO           | f         | PROMOTED      | 2
--   VCB           | f         | PROMOTED      | 2
```

**Idempotency:** re-running with `APPLY=1` after the first successful apply
inserts 0 rows — the `WHERE NOT EXISTS` guard skips strategies that already
have promotion-log entries.

**Rollback** (if backfill mis-targets, e.g. you decide a strategy should
not be in PROMOTED state):
```sql
DELETE FROM strategy_promotion_log
WHERE evidence->>'backfill' = 'true'
  AND account_strategy_id = '<the-mistargeted-id>';
```
After the targeted DELETE, fix the predicates in the script and re-run. The
`evidence->>'backfill' = 'true'` filter makes the backfill rows
distinguishable from genuine human promotions.

## Future backfills

When adding a new backfill, follow this pattern:

1. **Name format:** `V<N>_<description>_backfill.sql` matching the migration
   it depends on.
2. **APPLY flag:** support `--set=APPLY=1` for commit, default to dry-run.
3. **Idempotent:** `WHERE NOT EXISTS` or equivalent so re-running is safe.
4. **Identifiable:** writes carry a marker (e.g. `evidence->>'backfill'`)
   so the rows can be selected/deleted without ambiguity later.
5. **Readable verification query** at the end so the operator can sanity-
   check the result.
