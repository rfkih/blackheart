#!/usr/bin/env bash
# uninstall-windows-services.sh
#
# Stop and remove the Blackheart NSSM services. Run as Administrator.
# Does NOT touch the database, env files, JARs, or logs.

set -euo pipefail

NSSM="${NSSM:-nssm}"

for name in blackheart-research blackheart-trading; do
  if "$NSSM" status "$name" >/dev/null 2>&1; then
    echo "Stopping $name..."
    "$NSSM" stop   "$name" >/dev/null 2>&1 || true
    "$NSSM" remove "$name" confirm
  else
    echo "$name not installed; skipping."
  fi
done

echo "Done. Logs and config preserved under C:/project/."
