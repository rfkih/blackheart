# Blackheart — Fresh Production Migration (Windows + PostgreSQL 17)

> **For: a Claude Code session running on a NEW Windows production server.**
> Operator hands you the prompt: *"Read `deploy/MIGRATION_INSTRUCTIONS.md` and
> execute the migration."* Follow the steps below in order. Stop and ask the
> operator at every checkpoint marked **STOP-AND-ASK**.

---

## Mission

Bootstrap Blackheart on a clean Windows server with an **empty database**
(Option A — no historical data carried from the old environment). Install
both JVMs as Windows services via NSSM, verify they boot healthy, and hand
control back to the operator for first-time admin login.

**Do NOT** attempt to import historical trades, market data, accounts, or
backtest results. The operator will register accounts and Binance API keys
through the UI after migration.

---

## Hard rules

1. **Never use the dev sentinel values for secrets.** `DB_ENCRYPTION_KEY`,
   `JWT_SECRET`, `RESEARCH_ORCH_TOKEN`, and `DB_PASSWORD` MUST be replaced
   with real values. The trading JVM will refuse to boot under the `prod`
   profile if `DB_ENCRYPTION_KEY` is left at the default.
2. **Run shell scripts in Git Bash, as Administrator.** Right-click Git Bash
   icon → "Run as administrator". `nssm`, `psql`, and ACL changes all need
   elevation.
3. **PostgreSQL version is 17.** The Windows service is named
   `postgresql-x64-17`. Do not change to a different version.
4. **Install root is `C:\project\`** (in Git Bash: `/c/project/`). Do not
   relocate without operator approval.
5. **Do NOT touch the source database** on the old server — this migration
   is one-way (fresh start). The old server stays running as rollback.
6. **The schema file `deploy/schema/full_schema_v46.sql` is the source of
   truth.** Do not regenerate it or edit individual migrations during the
   migration.
7. **Stop and ask the operator** before running anything destructive
   (DROP, TRUNCATE, removing services, deleting files).

---

## Pre-flight checks

Before anything, verify the prerequisites are installed on this server.
Report the result of each check to the operator before continuing.

```bash
# 1. Run as Administrator? (cygpath check)
id -G | tr ' ' '\n' | grep -q '544' && echo "ADMIN: yes" || echo "ADMIN: NO — restart Git Bash as Administrator"

# 2. Java 21 (any vendor: Temurin, Oracle, Microsoft, etc.)
java -version 2>&1 | head -1
# Expect: openjdk version "21.x.x" or similar

# 3. PostgreSQL 17
psql --version
# Expect: psql (PostgreSQL) 17.x

# Service must be running:
sc query postgresql-x64-17 | grep STATE
# Expect: STATE : 4  RUNNING

# 4. NSSM
nssm version
# Expect: NSSM x.xx ...

# 5. Redis (Memurai or WSL redis-server)
sc query Memurai 2>/dev/null | grep STATE || echo "Memurai not installed; check WSL: wsl -- redis-cli ping"

# 6. Git
git --version

# 7. openssl (comes with Git for Windows)
openssl version
```

If any check fails, **STOP-AND-ASK** the operator to install the missing
component before proceeding. Common installs:

```bash
# Run as Administrator in PowerShell (NOT Git Bash):
choco install postgresql17 nssm git temurin21 memurai-developer
```

---

## Step 1 — Stage the repo and JARs

```bash
# Clone the repo if not already present
if [ ! -d /c/project/src/.git ]; then
  git clone <REPO_URL> /c/project/src
fi

# Create runtime layout
mkdir -p /c/project/{logs,data,research,config}

# Verify JARs exist (operator should have copied them already)
ls -la /c/project/blackheart-trading.jar /c/project/blackheart-research.jar
```

If JARs are missing, **STOP-AND-ASK**: "Where are the JARs? Should I build
them here from `/c/project/src` via `./gradlew bootJar`, or are they being
scp'd from another machine?"

---

## Step 2 — Create the empty database and DB roles

```bash
cd /c/project/src

# Verify trading_db doesn't exist yet (this script REQUIRES an empty DB)
psql -U postgres -lqt | cut -d'|' -f1 | tr -d ' ' | grep -qx trading_db && \
  echo "ERROR: trading_db already exists" || echo "OK: trading_db not present"
```

If `trading_db` already exists, **STOP-AND-ASK** the operator before
proceeding. Don't drop it without confirmation.

Otherwise, generate strong passwords and create the database + roles:

```bash
# Generate two strong passwords for the V14 roles. Save these — operator
# needs them for the env files in Step 4.
TRADING_PW=$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)
RESEARCH_PW=$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)

echo "TRADING role password:  $TRADING_PW"
echo "RESEARCH role password: $RESEARCH_PW"
echo ">>> RECORD THESE before continuing <<<"
```

**STOP-AND-ASK**: Print the passwords above and wait for the operator to
confirm they've been recorded (password manager / sealed envelope). Do
not proceed silently.

```bash
psql -U postgres <<SQL
CREATE DATABASE trading_db;
CREATE ROLE blackheart_trading  LOGIN PASSWORD '$TRADING_PW';
CREATE ROLE blackheart_research LOGIN PASSWORD '$RESEARCH_PW';
GRANT ALL PRIVILEGES ON DATABASE trading_db TO blackheart_trading;
GRANT CONNECT ON DATABASE trading_db TO blackheart_research;
SQL
```

---

## Step 3 — Apply the schema

```bash
cd /c/project/src
bash deploy/scripts/bootstrap-fresh-db.sh
```

The script:
- Validates the DB is empty (table count in `public` schema = 0).
- Applies `deploy/schema/full_schema_v46.sql` (creates all 50+ tables,
  indexes, triggers, V36 FCARRY seed, V1 FUNDING_INGEST scheduler row).
- Prompts whether to apply the admin user seed
  (`src/main/resources/db/migration/seed_admin_user.sql`, default password
  `admin123!`). **Answer `y`**; the operator will change the password
  immediately after first login.
- Runs a verification query: tables count, users count, strategy_definition
  count, scheduler_jobs count, accounts count.

Expected verification output:
- `tables_in_public` ≥ 40
- `users` = 1 (the seeded admin)
- `strategy_definition` ≥ 1 (FCARRY at minimum; LSR/VCB/VBO/TPR rows are
  added later via the UI/API)
- `scheduler_jobs` ≥ 1 (FUNDING_INGEST)
- `accounts` = 0
- `account_strategy` = 0

If the table count is far off (< 30), **STOP-AND-ASK** — the schema apply
likely failed mid-stream.

---

## Step 4 — Configure env files

Copy templates and fill in secrets:

```bash
cd /c/project/src
cp deploy/scripts/trading.env.example  /c/project/config/trading.env
cp deploy/scripts/research.env.example /c/project/config/research.env
```

### Generate the shared secrets

These two MUST be identical between `trading.env` and `research.env`:

```bash
DB_ENCRYPTION_KEY=$(openssl rand -base64 32)
JWT_SECRET=$(openssl rand -base64 32)
RESEARCH_ORCH_TOKEN=$(openssl rand -base64 32)

echo "DB_ENCRYPTION_KEY=$DB_ENCRYPTION_KEY"
echo "JWT_SECRET=$JWT_SECRET"
echo "RESEARCH_ORCH_TOKEN=$RESEARCH_ORCH_TOKEN"
```

Use `Edit` tool to update both env files. Replace every `CHANGE_ME` with
the right value. At minimum, in BOTH `trading.env` and `research.env`:

| Key | Value |
|---|---|
| `DB_PASSWORD` | `$TRADING_PW` (trading.env) / `$RESEARCH_PW` (research.env) |
| `DB_ENCRYPTION_KEY` | `$DB_ENCRYPTION_KEY` (same in both) |
| `JWT_SECRET` | `$JWT_SECRET` (same in both) |
| `RESEARCH_ORCH_TOKEN` | `$RESEARCH_ORCH_TOKEN` (same in both) |

For mail and Telegram, **STOP-AND-ASK** the operator:
- "Do you have Mailtrap (or other SMTP) credentials? If not, I'll set
  `MAIL_ENABLED=false` and `ALERTS_EMAIL_ENABLED=false`."
- "Do you have a Telegram bot token + chat IDs? If not, I'll set
  `ALERTS_TELEGRAM_ENABLED=false`."

Defaults to disable both if operator says no:
- `MAIL_ENABLED=false`
- `ALERTS_EMAIL_ENABLED=false`
- `ALERTS_TELEGRAM_ENABLED=false`

For CORS / cookie:
- If operator has not yet set up the frontend domain, leave
  `CORS_ALLOWED_ORIGINS=http://localhost:3000` and `COOKIE_SECURE=false`
  (dev-friendly for first boot).
- Once a real domain is wired up, the operator updates these and re-runs
  `install-windows-services.sh`.

### Lock down ACLs

```bash
icacls 'C:\project\config\trading.env'  //inheritance:r //grant:r 'Administrators:R' 'SYSTEM:R'
icacls 'C:\project\config\research.env' //inheritance:r //grant:r 'Administrators:R' 'SYSTEM:R'
```

Note the doubled `//` — Git Bash treats single `/` as a path separator and
mangles `icacls` flags otherwise.

### Sanity check

Confirm no `CHANGE_ME` remains:

```bash
grep -n CHANGE_ME /c/project/config/trading.env  /c/project/config/research.env
# Expect: no output
```

If any `CHANGE_ME` is left, **STOP-AND-ASK** — never start the JVM with
sentinel values.

---

## Step 5 — Install the Windows services

```bash
cd /c/project/src
bash deploy/scripts/install-windows-services.sh
```

The script (NSSM):
- Installs `blackheart-trading` service (heap 2g, profile=prod, port 8080).
- Installs `blackheart-research` service (heap 1.5g, profiles=prod,research, port 8081).
- Bakes the env file values into the service registry config.
- Sets dependencies: trading depends on `postgresql-x64-17`; research
  depends on `postgresql-x64-17` + `blackheart-trading`.
- Enables auto-restart on failure (10s delay).
- Configures stdout/stderr logging to `/c/project/logs/`.

Verify install:

```bash
nssm status blackheart-trading   # Expect: SERVICE_STOPPED
nssm status blackheart-research  # Expect: SERVICE_STOPPED
```

---

## Step 6 — Start and verify

Start trading first, verify health, then start research.

```bash
# Trading
nssm start blackheart-trading

# Wait up to 60s for it to come up; poll healthcheck:
for i in {1..30}; do
  if curl -fsS http://localhost:8080/healthcheck >/dev/null 2>&1; then
    echo "Trading JVM healthy after ${i}x2s"
    break
  fi
  sleep 2
done
curl -v http://localhost:8080/healthcheck
```

If healthcheck fails after ~60s:
1. Read the last 100 lines of `/c/project/logs/blackheart-trading-stderr.log`.
2. Common causes:
   - `Cannot connect to database`: DB password wrong, or postgres not running.
   - `Encryption key invalid`: `DB_ENCRYPTION_KEY` is the dev sentinel,
     or wrong base64 length (must decode to 32 bytes).
   - `Flyway baseline failed`: `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`
     is missing from `trading.env`.
3. Fix in env file → re-run `install-windows-services.sh` → restart.
4. **STOP-AND-ASK** if you can't diagnose within 2 attempts.

After trading is healthy:

```bash
nssm start blackheart-research
for i in {1..30}; do
  if curl -fsS http://localhost:8081/healthcheck >/dev/null 2>&1; then
    echo "Research JVM healthy after ${i}x2s"
    break
  fi
  sleep 2
done
curl -v http://localhost:8081/healthcheck
```

---

## Step 7 — Hand back to operator

Print this checklist for the operator and exit:

```
============================================================================
Migration complete. Operator next steps:

1. Login at http://localhost:8080  (or wherever you've configured the
   frontend) using:
     Email:    admin@blackheart.local       (from seed_admin_user.sql)
     Password: admin123!
   CHANGE THE PASSWORD IMMEDIATELY via the user settings page.

2. Register a Binance account in the UI:
     Settings -> Accounts -> New
     Enter your Binance API key and secret.
     The platform encrypts these with DB_ENCRYPTION_KEY before storing.

3. Enable strategies:
     Settings -> Account Strategies -> New
     Pick a strategy (LSR, VCB, VBO, TPR, FCARRY), bind to your account
     for BTCUSDT, set the simulated flag (start with simulated=true to
     paper-trade for 24h before going live).

4. Configure firewall:
     Open port 8080 only to your reverse proxy / frontend host.
     Port 8081 (research) MUST stay loopback-only.

5. Backup plan:
     Schedule a nightly pg_dump of trading_db to off-server storage.

6. Old server:
     Keep stopped but intact for at least 48h as rollback before
     decommissioning.
============================================================================
```

---

## STOP-AND-ASK checkpoints (full list)

You must pause and ask the operator at these points. Don't guess; don't
proceed silently:

1. Any pre-flight check fails (missing prereq).
2. Building JARs from source vs. expecting them pre-staged.
3. `trading_db` already exists.
4. Generated DB role passwords (operator must record them).
5. Mail / Telegram credentials (proceed with disabled if operator says no).
6. Frontend domain for CORS / cookie config.
7. Any `CHANGE_ME` left in the env files after Step 4.
8. Healthcheck fails after 2 troubleshooting attempts.
9. Anything destructive: DROP, TRUNCATE, removing existing services,
   deleting JARs, overwriting env files that already contain non-CHANGE_ME
   values.

---

## What NOT to do

- Do not run any `pg_restore` or import historical data — this is a fresh
  Option A migration.
- Do not modify migrations under `src/main/resources/db/flyway/`.
- Do not edit `src/main/resources/application.properties` to add secrets;
  secrets live in env files only.
- Do not start `blackheart-research` before `blackheart-trading` is healthy.
- Do not skip the `icacls` ACL lockdown — env files contain plaintext
  Binance and DB credentials.
- Do not commit `/c/project/config/*.env` to git. They live outside the
  repo on purpose.
- Do not regenerate `deploy/schema/full_schema_v46.sql`. Use the file
  as-shipped.

---

## Reference: file map

| Path | Purpose |
|---|---|
| `deploy/schema/full_schema_v46.sql` | All 46 Flyway migrations concatenated; bootstrap an empty DB. |
| `deploy/scripts/bootstrap-fresh-db.sh` | Applies schema + admin seed; verifies. |
| `deploy/scripts/install-windows-services.sh` | Installs both JVMs as NSSM services. |
| `deploy/scripts/uninstall-windows-services.sh` | Removes both NSSM services (preserves data). |
| `deploy/scripts/trading.env.example` | Template for trading JVM env (port 8080). |
| `deploy/scripts/research.env.example` | Template for research JVM env (port 8081). |
| `src/main/resources/db/migration/seed_admin_user.sql` | One-shot admin user seed. |
| `deploy/README.md` | Linux-focused deployment notes (less relevant here). |
| `CLAUDE.md` | Project-wide rules; read these and obey hard rules even mid-migration. |
