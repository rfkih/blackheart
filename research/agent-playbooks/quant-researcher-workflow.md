# Quant-researcher workflow playbook

> Step-by-step recipes the agent reads ON DEMAND (not in the system
> prompt). The agent prompt at `.claude/agents/quant-researcher.md`
> carries identity, mission, and hard constraints; this file carries
> the procedure.

## Workflow

### 0. Early-exit guard (run BEFORE any other work)

Burn windows take ~4h to drain. If a prior fire's work is still in flight there is no fresh evidence to react to and the right action is to log a no-op and exit — saves a full session of tokens.

```bash
source research/scripts/_env.sh

PENDING_ROWS=$(psql_cmd -t -c "SELECT COUNT(*) FROM research_queue
                               WHERE status IN ('PENDING','RUNNING');" \
               | tr -d '[:space:]')

LOG_AGE_SEC=999999
if [ -f /tmp/research-continuous.log ]; then
    LOG_AGE_SEC=$(( $(date +%s) - $(stat -c %Y /tmp/research-continuous.log) ))
fi

if [ "$PENDING_ROWS" -gt 0 ] && [ "$LOG_AGE_SEC" -lt 14400 ]; then
    psql_cmd -c "INSERT INTO research_journal
        (entry_type, title, content, structured_data, created_by)
        VALUES ('RUN_SUMMARY',
                'NO_OP — prior burn still draining',
                'Skipped fire: '"$PENDING_ROWS"' queue rows still PENDING/RUNNING and continuous log modified '"$LOG_AGE_SEC"'s ago.',
                '{\"pending\": '"$PENDING_ROWS"', \"log_age_sec\": '"$LOG_AGE_SEC"'}',
                'quant-researcher');"
    echo "Early exit: prior burn still in flight (pending=$PENDING_ROWS, log_age=${LOG_AGE_SEC}s)"
    exit 0
fi
```

If you exit here, do NOT continue to step 1. The next fire (6h later) will re-evaluate. The NO_OP entry is intentionally short.

### 1. Read state (every session start — non-negotiable)

Prefer the HTTP API (cold-boot routine below). Fall back to psql for cuts the API doesn't expose.

```bash
# A. One-screen summary + current rankings
bash research/scripts/research-status.sh
bash research/scripts/leaderboard.sh --top 15
bash research/scripts/leaderboard.sh --significant-only

# B. Durable "what's working / what's not"
source research/scripts/_env.sh

# All active STRATEGY_OUTCOME entries — the durable discard list
psql_cmd -c "SELECT strategy_code, LEFT(title,100), created_time
             FROM research_journal
             WHERE entry_type='STRATEGY_OUTCOME' AND status='ACTIVE'
             ORDER BY created_time DESC;"

# All active HYPOTHESIS entries — standing thesis to test or falsify
psql_cmd -c "SELECT strategy_code, LEFT(title,100), created_time
             FROM research_journal
             WHERE entry_type='HYPOTHESIS' AND status='ACTIVE'
             ORDER BY created_time DESC;"

# Last 7d of RUN_SUMMARY
psql_cmd -c "SELECT created_time, strategy_code, LEFT(title,80)
             FROM research_journal
             WHERE entry_type='RUN_SUMMARY'
               AND created_time > now() - interval '7 days'
             ORDER BY created_time DESC;"

# C. Per-strategy progress
psql_cmd -c "SELECT strategy_code,
                    COUNT(*)                                              AS iters_30d,
                    SUM(CASE WHEN verdict='SIGNIFICANT_EDGE' THEN 1 END)  AS sig,
                    SUM(CASE WHEN verdict='INSUFFICIENT_EVIDENCE' THEN 1 END) AS insuf,
                    SUM(CASE WHEN verdict='NO_EDGE' THEN 1 END)           AS no_edge,
                    MAX(profit_factor)                                    AS pf_peak,
                    MAX(created_time)                                     AS last_run
             FROM research_iteration_log
             WHERE created_time > now() - interval '30 days'
             GROUP BY strategy_code
             ORDER BY iters_30d DESC;"

# D. Queue right now
psql_cmd -c "SELECT strategy_code, status, iteration_number, iter_budget,
                    priority, LEFT(hypothesis,80)
             FROM research_queue
             WHERE status IN ('PENDING','RUNNING','PARKED')
             ORDER BY priority, created_time;"

# E. Recent plans
ls -t research/RESEARCH_PLAN_*.md 2>/dev/null | head -3
```

After A–E, write a **3-line "session brief"**: (1) what's currently winning, (2) what's discarded, (3) the standing hypothesis. Forces synthesis before action.

### 2. Form a hypothesis

The standing hypothesis comes from `HYPOTHESIS` entries you read in step 1B. Do not invent a new one without reading them.

If three or more strategies have failed the same hypothesis with sufficient iterations, write a new HYPOTHESIS entry marking the old one falsified (`UPDATE research_journal SET status='FALSIFIED' WHERE journal_id=...`) and propose the next thesis.

A useful hypothesis is falsifiable, names the dimension, predicts a direction (e.g. "tighter ATR-extreme gate on MMR raises avg MFE/MAE ratio without dropping n below 30").

### 3. Pick the experiment

Prefer in this order:

1. **Existing deployed strategy with unexplored dimensions** — cheapest. No deploys.
2. **Existing deployed strategy with stale verdict** — re-test on a different axis.
3. **New spec to author** — write YAML to `research/specs/<code>.yml` per `research/specs/SCHEMA.md` (4 archetypes: `mean_reversion_oscillator`, `trend_pullback`, `donchian_breakout`, `momentum_mean_reversion`). Hand to operator for deploy.

Do *not* re-sweep a strategy that has produced ≥5 INSUFFICIENT/NO_EDGE iterations on the same axis in the last 7 days. The orchestrator's re-discovery gate (`axis_previously_discarded` 409) will block it anyway.

### 4. Write the plan

Before queueing, write `research/RESEARCH_PLAN_<YYYY-MM-DD>.md` (one per session, overwrite if same day):
- **Premise** — current journal hypothesis + leaderboard state in 5 lines.
- **Constraints reaffirmed** — BTC/ETH only, intervals, prod untouchable, 10%/yr bar.
- **Experiments** — for each: strategy, hypothesis, sweep grid, iter budget, success criteria (V11 gates), branches.
- **Execution order** for this session.
- **Decision criteria for next session.**

### 5–6. Queue + run via HTTP API (preferred)

See "HTTP API recipes" below.

### 7. Read results, write journal

After each burn window:

```bash
psql_cmd -c "INSERT INTO research_journal
  (entry_type, strategy_code, title, content, structured_data, created_by)
  VALUES ('RUN_SUMMARY', <code-or-NULL>, <title>, <content>,
          '{\"iters\":N,\"verdicts\":{...}}', 'quant-researcher');"
```

Entry types you write:
- `RUN_SUMMARY` — every burn window. Iteration count + verdict tally. Mandatory.
- `HYPOTHESIS` — when data suggests a new structural insight. `status='ACTIVE'`.
- `STRATEGY_OUTCOME` — strategy hits DISCARD or PARKED with enough iterations. Cite iteration_log iteration_ids in `iteration_id_refs`. `status='ACTIVE'`.

When a HYPOTHESIS is contradicted by ≥3 strategies, mark old `status='FALSIFIED'` (do NOT delete) and write a fresh HYPOTHESIS row.

**Cross-reference**: include iteration_ids in `iteration_id_refs` whenever the entry is grounded in specific runs.

### 8. Decide next step

Recommend ONE of:
- **Park & hand back to operator** — SIGNIFICANT_EDGE candidate, walk-forward queued, user decides on promotion.
- **Continue research** — follow-up sweep on fresh dimension.
- **Pivot** — current archetype dead, propose next archetype.
- **Escalate** — 3+ archetypes failed same hypothesis → bottleneck is data plane (Phase 3) or methodology (Phase 9).

State recommendation in 3–4 sentences. Include evidence (iteration ids, journal refs).

---

## HTTP API recipes (preferred over bash)

### Auth + headers

```
X-Orch-Token: <ORCH_AUTH_TOKEN value>
X-Agent-Name: quant-researcher
Idempotency-Key: <uuid-per-logical-attempt>
```

`/healthz`, `/readyz`, `/agent/playbook` are public.

### Cold-boot routine

```bash
ORCH_BASE=http://127.0.0.1:8082
TOKEN=$(grep ^ORCH_AUTH_TOKEN research-orchestrator/.env | cut -d= -f2-)

curl -s "$ORCH_BASE/agent/playbook" | jq           # contract — read first
curl -s "$ORCH_BASE/readyz"                         # bail if degraded
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: quant-researcher" \
     "$ORCH_BASE/agent/state" | jq                  # one-shot snapshot
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: quant-researcher" \
     "$ORCH_BASE/journal?status=ACTIVE&entry_type=ANTI_PATTERN" | jq
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: quant-researcher" \
     "$ORCH_BASE/leaderboard?significant_only=true&limit=10" | jq
```

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET  | `/agent/playbook` | Discoverable contract |
| GET  | `/agent/state` | Snapshot |
| GET  | `/queue` / `/queue/{id}` | Browse queue, cursor-paginated |
| POST | `/queue` | Enqueue a sweep (replaces `queue-strategy.sh`) |
| POST | `/queue/{id}/cancel` | Park with reason |
| GET  | `/iterations` / `/iterations/{id}` / `/leaderboard` | Read iteration_log |
| GET  | `/journal` / `/journal/{id}` | Read journal |
| POST | `/tick` | One iteration end-to-end (replaces `research-tick.sh`) |
| POST | `/walk-forward` | 6-fold validation (replaces `walk-forward.sh`) |

### Enqueue a sweep

```bash
curl -s -X POST "$ORCH_BASE/queue" \
  -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: quant-researcher" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{
    "strategy_code": "MMR",
    "interval_name": "1h",
    "instrument": "BTCUSDT",
    "sweep_config": {"params": [
      {"name": "ATR_EXT", "values": ["1.5","2.0","2.5"]},
      {"name": "RSI_EXT", "values": ["20","25","30"]}
    ]},
    "hypothesis": "Tighter ATR-extreme + RSI-extreme raises MFE/MAE without n collapse",
    "iter_budget": 5
  }' | jq
```

Numeric param values are strings (BigDecimal on JVM side).

**Re-discovery gate (Tier 1, 2026-05-03):** the orchestrator returns 409 `axis_previously_discarded` if this strategy already produced a DISCARD on the same axis-set. Pass `override_discard_gate: true` only with documented justification (data backfill, entry-signal bug fix).

### Run a tick

```bash
curl -s -X POST "$ORCH_BASE/tick" \
  -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: quant-researcher" \
  -H "Idempotency-Key: $(uuidgen)" | jq
```

Synchronous up to ~30 min. Outcomes:
- `outcome=empty_queue` → consider POST `/queue`.
- `outcome=sweep_exhausted` → review iterations.
- `outcome=iterated` → follow `next_actions[]`. `verdict=PASS` next action points at `POST /walk-forward`.

For long burn windows, fire `/tick` repeatedly. DB-level `SKIP LOCKED` makes concurrent calls safe.

### Validate a SIGNIFICANT_EDGE iteration

```bash
curl -s -X POST "$ORCH_BASE/walk-forward" \
  -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: quant-researcher" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{
    "strategy_code": "MMR",
    "interval_name": "1h",
    "motivating_iteration_id": "<uuid-from-tick-response>",
    "overrides": {"ATR_EXT": "2.0", "RSI_EXT": "25"}
  }' | jq
```

Returns within ~3 hours (6 folds × up to 30min). `stability_verdict`: `ROBUST` is the only gate for graduation. `OVERFIT`/`INCONSISTENT` → re-design with regularisation. `NO_EDGE`/`INSUFFICIENT_EVIDENCE` → abandon.

### Error envelope

`{error_code, message, retryable, hint, next_action, details}`. Branch on `error_code`, never on `message`. `retryable=true` + `next_action.kind=='retry'` → wait `next_action.wait_s` and replay (same Idempotency-Key returns the original response). `retryable=false` → do not replay.

### What HTTP gives you that bash didn't

- **V11+ statistical gate is default.** `/tick` writes `verdict=PASS` only when n≥100, PF 95% CI lower>1.0, +20bps slippage>0, statistical_verdict=SIGNIFICANT_EDGE.
- **Walk-forward as a single call.**
- **Idempotent retries** (V28 Postgres-backed).
- **Re-discovery gate (Tier 1, 2026-05-03)**: `POST /queue` rejects sweeps whose axis-set has a prior DISCARD verdict on the same strategy. Prevents p-hacking by re-running the same dimensions.
- **Cumulative trial counter** (`hypothesis_audit` table): every tick records the trial; DSR `n_trials` reflects real selection-bias multiplicity, not naive grid size.

---

## Quant research craft (practices the orchestrator does NOT enforce)

### 1. State the hypothesis BEFORE the test

Format: **"I expect <effect> BECAUSE <mechanism>, and will measure success as <metric ≥ threshold>."**

- ✅ "4h VBO will outperform 1h on BTC because BB-width compression is more meaningful at lower frequencies; success = PF ≥ 1.4 across 6 walk-forward folds with std < 0.3."
- ❌ "Let's tune the ADX filter and see what passes." — exploratory dredging, not science.

Distinguish exploratory iterations (no statistical claim) from confirmatory iterations (single pre-registered test).

### 2. Multiple-testing discipline

- A 95% CI clears 5% of the time at random. Sweep 100 combos → 5 pass on noise alone.
- **Harvey, Liu, Zhu (2016)**: with K trials, t-stat threshold scales like √(2 log K). 64-cell sweep needs ~3.6, not naive 1.96.
- The orchestrator's DSR with cumulative_trials (Tier 1) auto-adjusts. When reading legacy PSR rows (no DSR), apply manual scaling:
  - 1 trial: PSR ≥ 0.95 fine.
  - 4-cell: ≥ 2 cells passing AND walk-forward ROBUST.
  - 16-cell: ROBUST + PSR ≥ 0.97.
  - 64+-cell: ROBUST + PSR ≥ 0.99 + held-out window.

### 3. Three-bucket discipline (in-sample / OOS / holdout)

- 2024–2026 window is **in-sample** — every sweep has touched it.
- Walk-forward is **rolling out-of-sample** but partition is itself a hyperparameter.
- A **held-out window** (last N months) you commit to never tuning on is the gold standard. Use `evaluateHoldout` once and accept the verdict.
- **Never iterate after a holdout failure.** Re-tuning on failure is data leakage.

### 4. Robustness > peak performance

- PF 1.4 std 0.2 across 6 folds beats PF 2.0 std 1.5.
- **Parameter stability**: optimal cell shouldn't be on a cliff. Adjacent cells within ~20% of optimum or it's curve-fit. Look for plateaus, not peaks.
- **Regime invariance**: positive expectation across dominant regimes. Use `by_trend_regime` and `by_quarter` from `regime_stratify`.

### 5. Costs, slippage, capacity

- +20bps gate is sanity, not realism. A strategy passing +20bps may still die at production turnover.
- Win-rate < 50% AND avg-winner / avg-loser < 1.5× = fragile to cost realism.
- **Capacity**: trade-size × n_trades vs BTC's median minute volume. > 0.1% of minute volume needs `capacity_usdt` annotation.

### 6. Iteration discipline

- 5 iterations per strategy is the cap. After: graduate to walk-forward, park as discarded, or document why another 5 are warranted.
- A passing cell at iter 1 (PF 1.5) is **more credible** than at iter 5 (PF 2.0) — the latter is curve-fitting.
- **DCT lesson**: clearing the bar with no margin in iter 1 is also a discard signal.

### 7. The journal is the audit trail of your reasoning

- Every discard gets a one-line reason.
- For confirmatory iterations, journal the **outcome vs prediction**: "Predicted PF ≥ 1.4 across folds, observed 1.6 mean / 0.18 std → hypothesis supported."

### 8. Sanity checks the orchestrator does not run

- **Look-ahead**: spec must only consume completed bars.
- **Survivorship**: not a concern with single-asset BTC; becomes one once Phase 3 multi-asset lands live.
- **Snapshot vs replay**: indicators with deep history (200-period EMA) — first 200 bars of any fold are unreliable; trim or warm-start.

### 9. Required reading

- Bailey & López de Prado (2014), *The Probabilistic Sharpe Ratio*.
- Bailey, Borwein, López de Prado, Zhu (2014), *Pseudo-Mathematics and Financial Charlatanism* — DSR.
- Harvey, Liu, Zhu (2016), *…and the Cross-Section of Expected Returns* — multiple testing.
- López de Prado, *Advances in Financial Machine Learning* — purged k-fold CV, embargo, leakage.

Cite these in journal entries when applying their lessons.

---

## Scripts (operator fallback)

| Script | Purpose | HTTP equivalent |
|---|---|---|
| `research-status.sh` | One-screen summary | `GET /agent/state` + `GET /leaderboard` |
| `leaderboard.sh` | Sortable rankings | `GET /leaderboard` |
| `queue-strategy.sh` | Add sweep row | `POST /queue` |
| `run-continuous.sh` | Burn-window driver | Loop `POST /tick` |
| `research-tick.sh` | Single tick | `POST /tick` |
| `log-iteration.sh` | Append iteration_log | (orchestrator does this) |
| `reconstruct-strategy.sh` | Reproduce iteration | (no HTTP equivalent) |
| `walk-forward.sh` | Validate windows | `POST /walk-forward` |
| `burn-queue-load.sh` | Operator preset | — |

Scripts you do **not** call:
- `deploy-from-spec.sh` / `deploy-strategy.sh` — restarts trading JVM, operator-only.
- `restart-service.sh` — touches live JVM.
- `dev-token-prod.sh` — needs prod creds.

---

## Mistakes to avoid

- **Single-dimension sweeps**: 1×3 grid burns budget. Always cross ≥ 2 dimensions.
- **Re-sweeping a parked strategy on the same axis** — the orchestrator's `axis_previously_discarded` 409 will block it; if it doesn't, read parked iterations first.
- **Promoting on n=1**: walk-forward is mandatory.
- **Skipping the journal write**: future-you reads it.
- **Confusing `research_queue` (orchestrator) with `/api/v1/research/sweeps`** — separate systems.
- **Calling `POST /tick` without an Idempotency-Key**: a network retry double-claims a queue row.
- **Treating `statistical_verdict=SIGNIFICANT_EDGE` as graduation-ready** — it isn't. Walk-forward `ROBUST` is the gate.
- **Treating BBR / DCT / CMR as candidates again** — discarded with prior evidence.
