# Common commands

> Build, run, and operations recipes. Loaded on demand. Top-level
> CLAUDE.md only points here.

## Build

```bash
./gradlew build                    # full build incl. tests
./gradlew compileJava              # fast Java-only compile
./gradlew tradingBootJar           # build/libs/blackheart-trading-*.jar (excludes research)
./gradlew researchBootJar          # build/libs/blackheart-research-*.jar (incl. dev-tooling)
./gradlew bootRun                  # dev-mode :8080 (DEPRECATED — use JAR)
```

## Run (production-grade two-JVM)

```bash
# Trading JVM (8080, long-lived)
java -Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
    -jar build/libs/blackheart-trading-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=dev --server.port=8080

# Research JVM (8081, restart-safe)
java -Xms512m -Xmx1500m \
    -jar build/libs/blackheart-research-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=dev,research --server.port=8081

# Or via launchers:
bash research/scripts/run-trading-service.sh --background
bash research/scripts/run-research-service.sh --background
bash research/scripts/watch-research-jvm.sh &  # research-JVM auto-restart watcher
```

## Research operations

**Primary path: research-orchestrator HTTP API** (port 8082, loopback). Agents+frontend use this; bash scripts are operator fallback.

```bash
# HTTP API (X-Orch-Token + X-Agent-Name headers)
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" http://127.0.0.1:8082/agent/state
curl -s -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" 'http://127.0.0.1:8082/leaderboard?limit=15'

# Tick (claim → submit → poll → analyse → write)
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: tick-$(date +%s)" http://127.0.0.1:8082/tick

# Walk-forward (after SIGNIFICANT_EDGE parks queue)
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: walk-$(date +%s)" \
     -d '{"queue_id":"...","n_folds":6}' http://127.0.0.1:8082/walk-forward

# Queue (agent boundary — orch token)
curl -X POST -H "X-Orch-Token: $TOKEN" -H "X-Agent-Name: ops" \
     -H "Idempotency-Key: q-$(date +%s)" \
     -d '{"strategyCode":"LSR","intervalName":"1h","sweepConfig":{...},"iterBudget":4}' \
     http://127.0.0.1:8082/queue
# Queue (frontend/user — JWT): POST /api/v1/research/queue/me

# Phase 2 spec workflow (still bash-driven)
python3 research/scripts/codegen-strategy.py --spec research/specs/<code>.yml --validate
bash research/scripts/deploy-from-spec.sh --spec research/specs/<code>.yml --interval 1h --sweep '...' --iter-budget 4

# Fallback bash (operator-only; do not script agents against these)
bash research/scripts/research-tick.sh
bash research/scripts/run-continuous.sh --hours 24
bash research/scripts/leaderboard.sh --top 15
bash research/scripts/queue-strategy.sh --code LSR --interval 1h --hypothesis "..." --sweep '...' --iter-budget N
bash research/scripts/reconstruct-strategy.sh <iter_id>
bash research/scripts/burn-queue-load.sh
```

## Test

```bash
./gradlew test
./gradlew test --tests "com.example.YourTest"
```

## Strategy Spec Language (Phase 2)

YAML specs codegen into Java. Editing YAML safer than Java. Templates in `research/templates/`: 4 archetypes (`mean_reversion_oscillator`, `trend_pullback`, `donchian_breakout`, `momentum_mean_reversion`) + `_common_helpers.java.tmpl` + `_common_params_helpers.java.tmpl`.

Pipeline: `research/specs/<code>.yml` → `codegen-strategy.py --validate --update-factory --check` → `deploy-from-spec.sh` (spec→codegen→factory wire→compile→restart→seed→queue).

```bash
python3 research/scripts/codegen-strategy.py --spec research/specs/your.yml --validate
bash research/scripts/deploy-from-spec.sh --spec research/specs/your.yml --interval 1h \
    --sweep '{"params":[{"name":"PARAM","values":[v1,v2,v3,v4]}]}' --iter-budget 4
```

Schema: `research/specs/SCHEMA.md`. Validator type-checks defaults, archetype-specific entry, version. Codegen ~290-line classes via `_common_helpers`. **Layer 2 autonomous gen** (CronCreate armed): agent picks unused archetype, writes YAML, runs `deploy-from-spec.sh`. **4h frequency cap** (`/tmp/last-strategy-gen.txt`). Compile-failure rollback via `deploy-strategy.sh` factory-restore.
