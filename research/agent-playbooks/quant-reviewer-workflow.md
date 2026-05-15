# Quant-reviewer workflow playbook

> Step-by-step recipes the reviewer reads ON DEMAND (not in the system
> prompt). Identity, mission, and hard constraints live in
> `.claude/agents/quant-reviewer.md`; this file is the procedure.

## Workflow

### 0. Cold-boot

```bash
ORCH_BASE=http://127.0.0.1:8082
TOKEN=$(grep ^ORCH_AUTH_TOKEN ../research-orchestrator/.env | cut -d= -f2-)

# Read contract
curl -s "$ORCH_BASE/agent/playbook" | jq

# Confirm orchestrator is reachable
curl -s "$ORCH_BASE/readyz"

# Pull pending review requests (FIFO)
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: quant-reviewer" \
     "$ORCH_BASE/reviews/pending" | jq
```

If `/reviews/pending` is empty there is nothing to do — exit with that summary line. Do not invent work.

### 1. For each pending request

The request row's `structured_data` contains:

```json
{
  "kind": "review_request",
  "target_id": "plan:MMR:abc123:hypothesis-uuid"  | "graduation:iteration-uuid",
  "target_kind": "plan" | "graduation",
  "request_payload": { /* see below */ },
  "requested_by": "quant-researcher"
}
```

For **plan** requests, `request_payload` looks like:

```json
{
  "strategy_code": "MMR",
  "axis_names": ["ATR_EXT", "RSI_EXT"],
  "hypothesis_id": "<journal_id of HYPOTHESIS row>",
  "plan_path": "research/RESEARCH_PLAN_2026-05-06.md",
  "notes": "..."
}
```

For **graduation** requests:

```json
{
  "iteration_id": "<uuid>",
  "strategy_code": "MMR",
  "motivating_hypothesis_id": "<uuid>",
  "notes": "..."
}
```

### 2. Fetch the artifacts

**Plan review.** Pull these in order:

```bash
# 2a. The pre-registered HYPOTHESIS journal entry (must predate the plan)
curl -s -H "X-Orch-Token: $TOKEN" \
     "$ORCH_BASE/journal/$HYPOTHESIS_ID" | jq

# 2b. Prior STRATEGY_OUTCOME entries on the same strategy (recent failures)
curl -s -H "X-Orch-Token: $TOKEN" \
     "$ORCH_BASE/journal?status=ACTIVE&entry_type=STRATEGY_OUTCOME&strategy_code=$STRATEGY_CODE&limit=20" | jq

# 2c. The plan file (read from disk if plan_path is provided)
cat $PLAN_PATH
```

**Graduation review.** Pull:

```bash
# 2d. The motivating iteration
curl -s -H "X-Orch-Token: $TOKEN" \
     "$ORCH_BASE/iterations/$ITERATION_ID" | jq

# 2e. The full sweep history for robustness check
curl -s -H "X-Orch-Token: $TOKEN" \
     "$ORCH_BASE/iterations?strategy_code=$STRATEGY_CODE&limit=50" | jq
```

### 3. Run the checklist

The mechanical checklists are pure functions in
`research-orchestrator/src/orchestrator/services/review.py`. You can:

- **Run them programmatically** via `python -c "from orchestrator.services.review import ..."` (PYTHONPATH=src) — most reliable.
- **Apply them by hand** against the artifacts — slower but lets you exercise judgement.

Either way, the structured findings list is what `POST /reviews` consumes.

#### Plan review checks (services/review.py)

| Check | Severity | Criterion |
|---|---|---|
| `pre_registration` | blocker | HYPOTHESIS journal entry strictly predates plan timestamp |
| `mechanism_named` | warning | HYPOTHESIS.content >= 60 chars AND contains a mechanism marker (because, due to, mechanism, rationale, etc.) |
| `axis_not_recently_failed` | warning | Same `axis_names` set has not produced a STRATEGY_OUTCOME on this strategy within the last 14 days |

#### Graduation review checks

| Check | Severity | Criterion |
|---|---|---|
| `n_trades_ample` | blocker | iteration.metrics_snapshot.analysis.n_trades >= 100 |
| `pf_ci_lower_bound` | blocker | confidence_intervals.pf_95.low > 1.0 |
| `dsr_threshold` | blocker | analysis.dsr >= 0.95 (with cumulative trial count) |
| `cost_realism` | warning | analysis.slippage_haircut_pnl["+50bps"] > 0 |
| `regime_concentration` | warning | All regimes with n>=5 in by_trend_regime have pnl > 0 |
| `portfolio_fit` | warning | portfolio_corr.max_abs_corr < 0.5 (or gate didn't apply) |
| `param_robustness` | warning | At least one Hamming-1 neighbour cell within 30% of optimum PF |

### 4. Aggregate the verdict

Mechanical rule (`aggregate_verdict`):

- **REJECTED** if any blocker check failed, OR if 2+ warning checks failed.
- **CONDITIONAL_APPROVAL** if exactly 1 warning check failed (researcher must acknowledge).
- **APPROVED** if no failures.

You may downgrade APPROVED → CONDITIONAL_APPROVAL when applying qualitative judgement (e.g., "all checks pass mechanically but the optimum cell sits at a 4-dim corner — flag for follow-up"). State the reason in `summary_reason`.

You may **never** upgrade. If the mechanical aggregator says REJECTED, your verdict must be REJECTED.

### 5. Post the verdict

```bash
curl -s -X POST "$ORCH_BASE/reviews" \
  -H "X-Orch-Token: $TOKEN" \
  -H "X-Agent-Name: quant-reviewer" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d @- <<EOF
{
  "target_id": "$TARGET_ID",
  "target_kind": "$TARGET_KIND",
  "verdict": "$VERDICT",
  "summary_reason": "$REASON",
  "summary_n_blocker_fails": $N_BLOCKERS,
  "summary_n_warning_fails": $N_WARNINGS,
  "motivating_request_id": "$REQUEST_ID",
  "strategy_code": "$STRATEGY_CODE",
  "findings": [
    {
      "check_name": "...",
      "severity": "blocker"|"warning"|"info",
      "passed": true|false,
      "finding": "...",
      "details": {...}
    }
  ]
}
EOF
```

The orchestrator closes the matching pending request automatically (status=PARKED) when the verdict posts.

### 6. End the session

Output the 5-line summary. Exit. Do not loop unless the operator told you to drain the queue.

---

## Decision discipline (industry best practices)

### 1. Independence

Read **only** the artifacts (journal, iteration_log, plan file). Do **not** read the researcher's chat context, thinking, or reasoning. Your verdict is informed by what was written down — that's what enforces the audit trail.

### 2. Specificity

Every REJECTED verdict must name a specific check, finding, and ideally a remediation hint. "Methodology weak" is not a verdict; "DSR=0.91 below 0.95 threshold at n_trials=20 — needs more trials or stricter PF bar" is.

### 3. No re-litigation

If you've already posted REJECTED on a target and the researcher re-submits with the same artifacts, post REJECTED again with the same findings. Re-running you in hopes of a different verdict is the kind of behaviour the audit trail catches.

### 4. CONDITIONAL_APPROVAL is not "yes with conditions"

It's "yes for this iteration, but the researcher must address the warning in the next round before re-graduation." The orchestrator gate accepts CONDITIONAL_APPROVAL the same as APPROVED for /queue and /walk-forward — that's deliberate, the researcher journals the acknowledgement and proceeds. But if the same warning appears in a follow-up review, you should escalate to REJECTED.

### 5. Escalation

If you see a pattern the checklist doesn't capture (e.g., the agent has run 8 archetypes in 24h and is clearly fishing), post a `CROSS_STRATEGY_FINDING` journal entry naming the pattern. That's outside the verdict and visible to the researcher's cold-boot.

### 6. When in doubt, REJECT

The cost of a false-negative (rejecting a real edge) is one extra round of work. The cost of a false-positive (approving a curve-fit that promotes to capital) is real money. The asymmetry favours strictness.

---

## Required reading for context

When evaluating findings, you may cite these in the journal:

- Bailey & López de Prado (2014), *The Probabilistic Sharpe Ratio*.
- Bailey, Borwein, López de Prado, Zhu (2014), *Pseudo-Mathematics and Financial Charlatanism* — DSR.
- Harvey, Liu, Zhu (2016), *…and the Cross-Section of Expected Returns* — multiple testing.
- López de Prado, *Advances in Financial Machine Learning* — purged k-fold, embargo, leakage.

---

## Mistakes to avoid

- **Reading the researcher's chat / thinking.** Independence is the contract.
- **Approving without specific findings.** APPROVED with empty findings list is a smell.
- **Re-running the checklist hoping for a different result.** Post the verdict once.
- **Designing experiments.** No "you should try ATR_EXT=2.5 instead." Stay in your role.
- **Self-approving.** Even if the operator invokes you twice, your second verdict is on a *new* request, not an override of the first.
- **Editing journal rows.** Append-only. Always.
- **Skipping the summary.** The operator reads it to decide whether to intervene.
