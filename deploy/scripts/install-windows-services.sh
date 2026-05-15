#!/usr/bin/env bash
# install-windows-services.sh
#
# Install (or reinstall) the Blackheart trading and research JVMs as Windows
# services using NSSM. Run in Git Bash on the Windows production host as
# Administrator.
#
# Prerequisites on the new host:
#   * NSSM installed and on PATH (https://nssm.cc/download or `choco install nssm`)
#   * Java 21 installed; java.exe at C:/Program Files/Java/jdk-21/bin/java.exe
#     (override via JAVA_EXE env var if installed elsewhere)
#   * PostgreSQL service running (default name 'postgresql-x64-17'; override
#     via PG_SERVICE env var)
#   * JARs staged at:
#       C:/project/blackheart-trading.jar
#       C:/project/blackheart-research.jar
#   * Env files populated at:
#       C:/project/config/trading.env
#       C:/project/config/research.env
#     (copy from trading.env.example / research.env.example, fill in secrets)
#   * Database bootstrapped via deploy/scripts/bootstrap-fresh-db.sh
#
# Run:
#   bash deploy/scripts/install-windows-services.sh
#
# After:
#   nssm start blackheart-trading
#   curl http://localhost:8080/healthcheck
#   nssm start blackheart-research
#   curl http://localhost:8081/healthcheck

set -euo pipefail

ROOT="${ROOT:-C:/project}"
JAVA_EXE="${JAVA_EXE:-C:/Program Files/Java/jdk-21/bin/java.exe}"
NSSM="${NSSM:-nssm}"
PG_SERVICE="${PG_SERVICE:-postgresql-x64-17}"

command -v "$NSSM" >/dev/null 2>&1 || { echo "ERROR: nssm not found in PATH"; exit 1; }
[[ -f "$JAVA_EXE" ]] || { echo "ERROR: java.exe not found at: $JAVA_EXE"; exit 1; }

mkdir -p "$ROOT/logs" "$ROOT/data" "$ROOT/research" "$ROOT/config"

install_service() {
  local name="$1" jar="$2" env_file="$3" profiles="$4" heap_min="$5" heap_max="$6"

  [[ -f "$jar" ]]      || { echo "ERROR: jar not found: $jar"; exit 1; }
  [[ -f "$env_file" ]] || { echo "ERROR: env file not found: $env_file"; exit 1; }

  echo
  echo "=== $name ==="

  if "$NSSM" status "$name" >/dev/null 2>&1; then
    echo "Service exists; stopping and removing for clean reinstall."
    "$NSSM" stop   "$name" >/dev/null 2>&1 || true
    "$NSSM" remove "$name" confirm >/dev/null
  fi

  "$NSSM" install "$name" "$JAVA_EXE"
  "$NSSM" set "$name" AppParameters "-Xms${heap_min} -Xmx${heap_max} -jar \"$jar\" --spring.profiles.active=${profiles}"
  "$NSSM" set "$name" AppDirectory  "$ROOT"
  "$NSSM" set "$name" DisplayName   "Blackheart $name"
  "$NSSM" set "$name" Description   "Blackheart $name JVM (profiles: $profiles)"
  "$NSSM" set "$name" Start         SERVICE_AUTO_START
  "$NSSM" set "$name" AppStdout     "$ROOT/logs/${name}-stdout.log"
  "$NSSM" set "$name" AppStderr     "$ROOT/logs/${name}-stderr.log"
  "$NSSM" set "$name" AppRotateFiles 1
  "$NSSM" set "$name" AppRotateBytes 104857600        # 100 MB rotation
  "$NSSM" set "$name" AppExit Default Restart
  "$NSSM" set "$name" AppRestartDelay 10000           # 10s before restart
  "$NSSM" set "$name" AppStopMethodConsole 30000      # 30s for graceful Spring shutdown

  # Parse env file: pass each non-comment, non-empty KEY=VALUE line to NSSM.
  local env_args=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"                              # strip Windows CR
    [[ -z "$line" ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    env_args+=("$line")
  done < "$env_file"

  if (( ${#env_args[@]} == 0 )); then
    echo "WARNING: no env vars parsed from $env_file"
  else
    "$NSSM" set "$name" AppEnvironmentExtra "${env_args[@]}"
  fi
}

install_service "blackheart-trading" \
                "$ROOT/blackheart-trading.jar" \
                "$ROOT/config/trading.env" \
                "prod" \
                "2g" "2g"

install_service "blackheart-research" \
                "$ROOT/blackheart-research.jar" \
                "$ROOT/config/research.env" \
                "prod,research" \
                "512m" "1500m"

# Service start order: Postgres -> trading -> research
if "$NSSM" status "$PG_SERVICE" >/dev/null 2>&1; then
  "$NSSM" set blackheart-trading  DependOnService "$PG_SERVICE"
  "$NSSM" set blackheart-research DependOnService "$PG_SERVICE" blackheart-trading
  echo
  echo "Set service dependencies: postgres -> trading -> research"
else
  echo
  echo "WARNING: postgres service '$PG_SERVICE' not found. Skipping DependOnService."
  echo "         Set PG_SERVICE env var to your actual postgres service name."
fi

cat <<'NEXT'

============================================================================
Services installed. Next steps:

1. Start trading and verify health:
     nssm start blackheart-trading
     curl http://localhost:8080/healthcheck

2. After trading is healthy, start research:
     nssm start blackheart-research
     curl http://localhost:8081/healthcheck

3. Tail logs:
     tail -f /c/project/logs/blackheart-trading-stdout.log

4. Stop:
     nssm stop blackheart-research
     nssm stop blackheart-trading

5. Re-run this script after editing env files; it stops, removes, and
   reinstalls each service cleanly.
============================================================================
NEXT
