#!/usr/bin/env bash
# bootstrap-fresh-db.sh
#
# Initialize a fully empty Blackheart database on a new production host.
# Run this ON THE NEW SERVER, after Postgres is installed and the
# trading_db database exists.
#
# This is for Option A in the migration plan: no historical data carried
# over from the old environment. Operator registers accounts, links
# strategies, and enters Binance API keys via the API/UI after JVM boot.
#
# Prerequisites on the new server:
#   * Postgres 14+ running, listening on the configured host/port
#   * trading_db database exists and is EMPTY (createdb trading_db)
#   * psql in PATH; credentials with CREATE/INSERT on trading_db
#   * This repo cloned (or these files scp'd into place):
#       - deploy/schema/full_schema_v46.sql
#       - src/main/resources/db/migration/seed_admin_user.sql
#
# After this script completes:
#   1. Edit /etc/blackheart/trading.env  AND  /etc/blackheart/research.env:
#        SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
#        SPRING_FLYWAY_BASELINE_VERSION=46
#        DB_ENCRYPTION_KEY=<openssl rand -base64 32>
#        JWT_SECRET=<openssl rand -base64 32>
#        ...plus all other env vars listed in deploy/README.md
#   2. Start the trading JVM, then research JVM. On first boot Flyway
#      will record V46 as the baseline and skip all migrations.
#   3. Login as admin (default password from seed_admin_user.sql -- CHANGE
#      IT FIRST), register a Binance account, enter API keys via the UI,
#      and enable strategies on account_strategy.

set -euo pipefail

DB_NAME="${DB_NAME:-trading_db}"
DB_USER="${DB_USER:-postgres}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
SCHEMA_FILE="${SCHEMA_FILE:-deploy/schema/full_schema_v46.sql}"
ADMIN_SEED_FILE="${ADMIN_SEED_FILE:-src/main/resources/db/migration/seed_admin_user.sql}"
APPLY_ADMIN_SEED="${APPLY_ADMIN_SEED:-prompt}"   # yes | no | prompt

PSQL=( psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 )

echo "=== Pre-flight checks ==="
[[ -f "$SCHEMA_FILE" ]] || { echo "ERROR: $SCHEMA_FILE not found"; exit 1; }
[[ -f "$ADMIN_SEED_FILE" ]] || { echo "ERROR: $ADMIN_SEED_FILE not found"; exit 1; }
"${PSQL[@]}" -tAc "SELECT 1" > /dev/null || { echo "ERROR: cannot connect to $DB_HOST:$DB_PORT/$DB_NAME as $DB_USER"; exit 1; }

EXISTING=$( "${PSQL[@]}" -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'" )
if [[ "$EXISTING" -gt 0 ]]; then
  echo "WARNING: $DB_NAME already has $EXISTING tables in 'public' schema."
  echo "         This script expects an EMPTY database. Aborting."
  echo "         If you really want to wipe and reinit:"
  echo "           DROP DATABASE $DB_NAME; CREATE DATABASE $DB_NAME;"
  exit 1
fi

echo "=== 1/3  Applying full schema (V1 -> V46) ==="
"${PSQL[@]}" -f "$SCHEMA_FILE"

echo "=== 2/3  Admin user seed ==="
case "$APPLY_ADMIN_SEED" in
  yes)
    echo "Applying admin seed (APPLY_ADMIN_SEED=yes)."
    "${PSQL[@]}" -f "$ADMIN_SEED_FILE"
    ;;
  no)
    echo "Skipping admin seed (APPLY_ADMIN_SEED=no). You must seed an admin"
    echo "user before logging in to the JVM."
    ;;
  prompt|*)
    echo "Default admin seed installs password 'admin123!' — CHANGE IT before"
    echo "this server is reachable from outside."
    read -r -p "Apply admin seed now? [y/N] " ans
    if [[ "$ans" =~ ^[Yy]$ ]]; then
      "${PSQL[@]}" -f "$ADMIN_SEED_FILE"
    else
      echo "Skipped. Apply manually later: psql ... -f $ADMIN_SEED_FILE"
    fi
    ;;
esac

echo "=== 3/3  Verification ==="
"${PSQL[@]}" -c "
SELECT 'tables_in_public'      AS what, count(*)::text AS n FROM information_schema.tables WHERE table_schema='public'
UNION ALL SELECT 'users',                 count(*)::text FROM users
UNION ALL SELECT 'strategy_definition',   count(*)::text FROM strategy_definition
UNION ALL SELECT 'scheduler_jobs',        count(*)::text FROM scheduler_jobs
UNION ALL SELECT 'accounts',              count(*)::text FROM accounts
UNION ALL SELECT 'account_strategy',      count(*)::text FROM account_strategy;
"

cat <<'NEXT'

============================================================================
Done. Next steps:

1. Generate fresh secrets on this host:
     openssl rand -base64 32   # for DB_ENCRYPTION_KEY
     openssl rand -base64 32   # for JWT_SECRET

2. Edit /etc/blackheart/trading.env and /etc/blackheart/research.env:
     DB_URL=jdbc:postgresql://<db-host>:5432/trading_db
     DB_USERNAME=blackheart_trading        # or blackheart_research
     DB_PASSWORD=<password>
     DB_ENCRYPTION_KEY=<from step 1>
     JWT_SECRET=<from step 1>
     SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
     SPRING_FLYWAY_BASELINE_VERSION=46
     ... plus the rest from deploy/README.md

3. Start the trading JVM, verify health:
     sudo systemctl start blackheart-trading
     curl -fsS http://localhost:8080/healthcheck

4. Login as admin (change password immediately), register a Binance
   account in the UI, enter API keys, and enable strategies on
   account_strategy.
============================================================================
NEXT
