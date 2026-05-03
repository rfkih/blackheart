# Quant-developer workflow playbook

> Step-by-step recipes the agent reads ON DEMAND. The agent prompt at
> `.claude/agents/quant-developer.md` carries identity, mission, and
> hard constraints; this file carries procedure.

## Workflow

### 0. Early-exit guard

If the prior session is still in flight or there are zero new errors AND no open development tasks, log a no-op and exit.

```bash
source research/scripts/_env.sh

NEW_ERRORS=$(psql_cmd -t -c "SELECT COUNT(*) FROM error_log
                             WHERE status IN ('NEW','INVESTIGATING');" \
             | tr -d '[:space:]')

if [ "$NEW_ERRORS" -eq 0 ]; then
    echo "No open errors; exiting clean."
    exit 0
fi
```

If you exit here, do NOT continue. The next daily fire re-evaluates.

### 1. Read state (every session start — non-negotiable)

```bash
source research/scripts/_env.sh

# A. Triage queue ordered by severity then recency
psql_cmd -c "SELECT error_id, severity, jvm, logger_name,
                    occurrence_count, last_seen_at,
                    LEFT(message, 80) AS msg, exception_class
             FROM error_log
             WHERE status IN ('NEW','INVESTIGATING')
             ORDER BY CASE severity
                        WHEN 'CRITICAL' THEN 1
                        WHEN 'HIGH'     THEN 2
                        WHEN 'MEDIUM'   THEN 3
                        WHEN 'LOW'      THEN 4
                      END,
                      last_seen_at DESC
             LIMIT 20;"

# B. Recently resolved (last 7d)
psql_cmd -c "SELECT error_id, severity, logger_name,
                    LEFT(message, 80) AS msg, resolved_at, resolved_by
             FROM error_log
             WHERE status='RESOLVED'
               AND resolved_at > now() - interval '7 days'
             ORDER BY resolved_at DESC LIMIT 10;"

# C. Recurrent fingerprints (fix didn't hold)
psql_cmd -c "SELECT fingerprint, COUNT(*) AS opens, MAX(last_seen_at) AS last_seen
             FROM error_log
             GROUP BY fingerprint
             HAVING COUNT(*) > 1
             ORDER BY opens DESC, last_seen DESC LIMIT 10;"

# D. Recent strategy-file commits
git log --oneline -20 -- src/main/java/id/co/blackheart/service/strategy/ \
                          src/main/java/id/co/blackheart/dto/strategy/ 2>/dev/null
```

After A–D, write a **3-line "session brief"**: (1) open errors per severity, (2) recurrent fingerprints, (3) strategy you intend to investigate first.

### 2. Pick one error to triage

Order by severity desc, then `last_seen_at` desc. CRITICAL first, always.

For each candidate, decide:
- **FIX** — clear cause, file in non-protected zone (see step 3).
- **INVESTIGATE** — cause unclear. Read related code, write finding, mark `status='INVESTIGATING'`.
- **WONT_FIX / IGNORED** — known noise.
- **ESCALATE** — cause sits in protected file. Write a finding describing fix you would make.

Never silently mark RESOLVED without a code change.

**Cap yourself to 3 fixes per session.** Beyond that, safety-gate backtests eat the wall-clock budget.

### 3. Allowed edit zones

**Allowed (non-protected):**
- `service/strategy/TrendPullbackStrategyService.java` (TPR — research)
- `service/strategy/ChopMeanReversionStrategyService.java` (CMR — research)
- `service/strategy/BollingerReversalStrategyService.java` (BBR — discarded)
- `service/strategy/ExecutionTestService.java` (TEST)
- Any newly-introduced strategy whose `STRATEGY_CODE` is not in the protected set.
- Bug fixes inside `service/observability/` (never if doing so silences an error you should fix).
- Test-only code under `src/test/`.

**Forbidden (protected):**
- `service/strategy/LsrStrategyService.java`
- `service/strategy/VcbStrategyService.java`
- `service/strategy/VolatilityBreakoutStrategyService.java`
- `dto/lsr/`, `dto/vcb/`, `dto/vbo/`
- `model/LsrStrategyParam.java`, `model/VcbStrategyParam.java`, `model/VboStrategyParam.java`
- `service/strategy/LsrStrategyParamService.java`, `VcbStrategyParamService.java`, `VboStrategyParamService.java`
- `service/live/`, `service/trade/`, `client/BinanceClientService*`, `stream/BinanceWebSocketClient*` — live trading hot path.
- Any Flyway migration under `db/flyway/V<N>__*.sql` already in `flyway_schema_history`.
- `engine/StrategyEngine*.java`, `engine/SpecDriven*.java` — parametric engine internals.

When a fix sits in a forbidden zone: STOP. Write a finding describing what you would change and why; hand back.

### 4. Make the edit (on a branch — never on `main`)

One change per fix. No piggybacking unrelated cleanups.

```bash
# A. Start clean from main
git fetch origin --prune
git switch main
git pull --ff-only origin main
git status --porcelain                # MUST be empty

# B. Create fresh branch (error id 8-char prefix + slug)
ERR_ID_SHORT=$(echo "<error_id>" | cut -c1-8)
SLUG="<short-kebab-case-summary>"
BRANCH="dev/quant-developer/${ERR_ID_SHORT}-${SLUG}"
git switch -c "$BRANCH"

# C. Confirm zone, read context, edit minimally, compile.
./gradlew compileJava 2>&1 | tail -30
```

If compile fails, throw the branch away (`git switch main; git branch -D "$BRANCH"`) and write a finding. Don't try to fix the fix.

If compile passes, **do not commit yet** — capture baseline first.

### 5. Capture safety-gate baseline (do NOT execute the backtest)

You run on a workstation; the research JVM that drains `research_queue` runs on the VPS with unmodified `main`. A queued backtest there re-runs old code. Document the baseline; the operator runs the actual gate locally pre-merge.

```bash
source research/scripts/_env.sh

psql_cmd -c "SELECT iteration_id, profit_factor, net_profit,
                    total_trades, max_drawdown_pct,
                    statistical_verdict, created_time
             FROM research_iteration_log
             WHERE strategy_code = '<CODE>'
             ORDER BY (statistical_verdict = 'SIGNIFICANT_EDGE') DESC,
                      created_time DESC
             LIMIT 5;"
```

Capture: baseline `iteration_id`, `profit_factor`, `total_trades`, `statistical_verdict`. Goes into commit message.

Acceptance bar (operator's run, not yours):
- Statistical verdict must not regress (SIGNIFICANT → INSUFFICIENT or INSUFFICIENT → NO_EDGE blocks merge).
- `profit_factor` ≥ baseline within bootstrap CI overlap.
- `total_trades` not collapsed (>50% drop = entry shape changed accidentally).
- Slippage-adjusted PnL at +20bps still positive vs baseline.

If no baseline exists for the strategy: STOP and escalate (finding row, do not push).

### 6. Commit + push the branch

```bash
git add -A
git commit -m "$(cat <<EOF
fix(<scope>): <one-line summary>

Closes error_log row <error_id>.
Fingerprint: <fp>
Severity: <CRITICAL|HIGH|MEDIUM>

Root cause:
<one paragraph>

Fix:
<one paragraph>

Safety-gate baseline (operator must run before merging):
  Strategy:       <CODE>
  Baseline iter:  <iteration_id>
  Baseline PF:    <pf>     (n=<total_trades>, verdict=<statistical_verdict>)
  Acceptance:     PF ≥ <pf> (CI overlap), n drop < 50%, verdict not regressed,
                  +20bps slip still positive

To reproduce:
  git checkout <branch>
  ./gradlew researchBootJar
  bash research/scripts/reconstruct-strategy.sh <iteration_id> --queue-rerun
  bash research/scripts/research-tick.sh
  # compare result row in research_iteration_log against baseline above
EOF
)"
git push -u origin "$BRANCH"

gh pr create --draft --base main --head "$BRANCH" \
  --title "fix(<scope>): <one-line summary>" \
  --body "Auto-generated by quant-developer agent. See commit message for reproduction recipe."
```

The branch + draft PR is the **handoff** — your job ends here. You do NOT merge, run the gate, deploy, or touch the trading VPS.

### 7. Update error_log + write finding

```sql
UPDATE error_log
   SET status = 'RESOLVED',
       resolved_at = now(),
       resolved_by = 'quant-developer',
       developer_finding_id = :finding_id
 WHERE error_id = :err_id;

INSERT INTO code_review_finding
  (finding_id, error_log_id, severity, status,
   summary, root_cause, fix_summary, files_changed,
   branch_name, commit_sha, baseline_iter_id,
   created_by, created_at)
VALUES
  (gen_random_uuid(), :err_id, :severity, :status,
   :summary, :root_cause, :fix_summary, :files_changed::text[],
   :branch_name, :commit_sha, :baseline_iter_id,
   'quant-developer', now())
RETURNING finding_id;
```

`status` values:
- `FIXED` — branch pushed, awaiting operator merge. Set branch + commit + baseline.
- `INVESTIGATING` — looked at, cause unclear. Branch fields NULL.
- `ESCALATED` — cause in protected zone. Branch NULL; root_cause + fix_summary describe what you'd do.
- `WONT_FIX` — known noise / intentional fail-fast.
- `NO_OP` — early-exit row.

Findings are append-only — never UPDATE; corrections are fresh rows that cite the prior in `root_cause`.

### 8. Stop conditions

Stop when ANY:
- 3 branches pushed.
- Remaining errors all in forbidden zones (write findings, exit).
- No defensible baseline for touched strategy.
- Compile fails twice on different attempts.
- `error_log` queue empty.

---

## Reading data (canonical queries)

```sql
-- One open error full detail
SELECT * FROM error_log WHERE error_id = :id;

-- All occurrences of a fingerprint historically
SELECT error_id, status, occurred_at, last_seen_at,
       occurrence_count, severity
FROM error_log
WHERE fingerprint = :fp
ORDER BY occurred_at DESC;

-- Strategy baseline (last SIGNIFICANT_EDGE)
SELECT iteration_id, params_snapshot, profit_factor,
       sharpe_ratio, total_trades, max_drawdown_pct, verdict,
       statistical_verdict, created_time
FROM research_iteration_log
WHERE strategy_code = :code
  AND statistical_verdict = 'SIGNIFICANT_EDGE'
ORDER BY created_time DESC
LIMIT 1;
```

## Mistakes to avoid

- **Editing a protected strategy because the fingerprint pointed there.** The error may originate in a protected file but the fix may belong in a caller's null-check or config — find the right zone.
- **Pushing a branch without a baseline `iteration_id`** — operator has no acceptance bar.
- **Trying to run the safety-gate backtest yourself.** VPS still runs `main`; document instead.
- **Past the 3-fix cap.** Budget exists for a reason.
- **Marking RESOLVED without a finding.** Future-you will see closed-with-no-record and re-diagnose.
- **Silencing errors with try/catch + log.warn.** Default for "I don't understand" is INVESTIGATING + escalate.
- **Restarting the trading JVM.** Operator-only.
- **Calling research scripts** (`run-continuous.sh`, `queue-strategy.sh`) — that's quant-researcher's lane.
- **Committing on `main`.** Always branch first; if you forget, `git stash` + `git switch -c <branch>` + `git stash pop`.
- **Bundling multiple fixes into one branch.** One error_id → one branch → one commit.
- **Pushing without compile + baseline.** Discard locally (`git branch -D`), don't push.

## When you don't know

- Stack trace points into a Spring framework class? Bug is almost always in our code, one frame up. Re-read from the bottom of *our* package.
- Fix would touch a protected file? STOP. Write a finding, hand back.
- `compileJava` fails after edit? Revert immediately.
- No baseline iteration for touched strategy? Don't invent one. Write finding, mark INVESTIGATING.
