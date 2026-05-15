# Postgres migration — home PC Docker → VPS

> Move `trading_db` from the local Docker container (`blackheart-postgres`)
> to a fresh Postgres on your VPS. Once complete, the trading JVM (on VPS)
> talks to localhost Postgres; the research JVM (on home PC) reaches the
> same DB over Tailscale.

## Inventory (snapshot at time of writing)

- **Source**: `docker exec blackheart-postgres` on home PC, DB `trading_db`
- **Size**: ~4.4 GB total
- **Roles to recreate**: `blackheart_research`, `blackheart_trading`
- **Extensions**: none custom (only the implicit `plpgsql`)
- **Flyway head**: V58
- **Top tables**:
  - `spec_trace` — **3.8 GB** (research spec-execution diagnostics)
  - `feature_store` — 364 MB
  - `market_data` — 102 MB
  - `backtest_trade` + `backtest_equity_point` + `backtest_trade_position` — ~130 MB combined
  - Everything else — <2 MB combined

`spec_trace` is the dominant cost. You have a choice:

- **Option X — Move everything** (simple, ~4.4 GB dump). Total downtime
  ~30–45 min. Best if you might want to audit old research runs.
- **Option Y — Truncate `spec_trace` first** then move (cuts the dump to
  ~600 MB). Total downtime ~10–15 min. Best if you've already shipped
  the strategies the traces were diagnosing. The table will start
  filling again from the next research run.

Pick before starting; both paths are documented below.

## Decision matrix before you start

| Item | Recommendation | Rationale |
|---|---|---|
| Where Postgres lives going forward | VPS (alongside trading JVM) | Live trading writes are hot-path; research reads tolerate Tailscale RTT |
| `trading_db` name on VPS | Same (`trading_db`) | Avoids changing every code reference |
| `postgres` superuser password on VPS | **New strong value** | Do not reuse the dev `admin` password |
| `blackheart_research` / `blackheart_trading` passwords | **New strong values** | Do not reuse the dev `research_dev_pass` / `trading_dev_pass` |
| Postgres major version on VPS | **16** (matches local — `PostgreSQL 16.13`) | Cross-major-version `pg_restore` can fail on certain catalog views |

Generate three passwords up front, store in a password manager:

```bash
openssl rand -base64 24    # POSTGRES_PASSWORD       (superuser)
openssl rand -base64 24    # BLACKHEART_TRADING_PASS
openssl rand -base64 24    # BLACKHEART_RESEARCH_PASS
```

## Pre-flight (no changes yet)

### 1. Confirm Flyway head matches code

```bash
docker exec blackheart-postgres psql -U postgres -d trading_db -c \
  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```

Expect V58 at the top with `success = t`. If a row has `success = f`,
**stop and fix it first** — restoring a partially-applied schema to a
fresh Postgres will brick the trading JVM on boot.

### 2. Confirm no in-flight backtests

```bash
docker exec blackheart-postgres psql -U postgres -d trading_db -c \
  "SELECT id, status, progress_percent FROM backtest_run WHERE status IN ('PENDING','RUNNING');"
```

If any are RUNNING, either wait for them to finish or accept that they
won't resume on the new DB (the run row will sit at PENDING; you can
re-submit after migration).

## Phase 1 — Stop services + take baseline backup

On home PC:

```powershell
# Stop trading JVM (if running) and research JVM.
# Trading JVM owns the live writes; pausing it gives a clean point-in-time
# dump. Research JVM only reads, but stopping avoids stale connections.

# (If running via gradle bootRun in IntelliJ, hit Stop in the IDE.
#  If as a Windows service or manual java process, kill it.)
```

(If you chose **Option Y**) Truncate spec_trace before dumping:

```bash
docker exec blackheart-postgres psql -U postgres -d trading_db -c \
  "TRUNCATE TABLE spec_trace RESTART IDENTITY;"
```

Take the dump. `-Fc` is the custom binary format — supports parallel
restore and is the right choice for everything except tiny dev DBs:

```bash
# Both paths use the same dump command — Option Y just runs against a
# smaller starting state.
docker exec blackheart-postgres pg_dump \
  -U postgres -Fc --no-owner --no-acl \
  -d trading_db \
  -f /tmp/trading_db.dump

# Copy out of the container to the host filesystem:
docker cp blackheart-postgres:/tmp/trading_db.dump C:\Project\trading_db.dump
```

`--no-owner` + `--no-acl` strips ownership/grant statements from the
dump so it restores cleanly even if the destination DB has different
role names or grant policies. We'll recreate roles + grants explicitly
on the VPS.

Verify the dump is readable:

```bash
docker exec blackheart-postgres pg_restore -l /tmp/trading_db.dump | head -20
```

You should see a list of database objects (tables, indexes, FKs). If
pg_restore errors out, **do not proceed** — the dump is corrupt.

## Phase 2 — Provision Postgres on the VPS

If your VPS doesn't have Docker yet:

```bash
# Ubuntu / Debian
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
# log out + back in for group membership to take effect
```

Create the Postgres compose file on the VPS:

```bash
mkdir -p ~/blackheart/postgres && cd ~/blackheart/postgres
cat > docker-compose.yml <<'YAML'
services:
  postgres:
    image: postgres:16
    container_name: blackheart-postgres
    restart: unless-stopped
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set in .env}
      POSTGRES_DB: trading_db
    volumes:
      - pgdata:/var/lib/postgresql/data
    # IMPORTANT: bind to localhost AND the Tailscale interface, NOT 0.0.0.0.
    # localhost so the trading JVM on this VPS connects cheaply; Tailscale
    # so the research JVM on the home PC can reach it.
    # Pick ONE of the two ports blocks below:

    # (a) If trading JVM runs natively on the VPS host (not in Docker):
    ports:
      - "127.0.0.1:5432:5432"
      - "<TAILSCALE_VPS_IP>:5432:5432"

    # (b) If trading JVM runs in Docker on the same compose project:
    #   leave ports section empty, use the compose network instead.

volumes:
  pgdata:
YAML

cat > .env <<EOF
POSTGRES_PASSWORD=<paste superuser password from earlier>
EOF
chmod 600 .env

docker compose up -d
docker compose logs -f postgres   # wait for "database system is ready to accept connections"
# Ctrl-C to exit the logs follow
```

Sanity-check the connection from the VPS shell:

```bash
docker exec blackheart-postgres psql -U postgres -d trading_db -c "SELECT version();"
# → PostgreSQL 16.x on ...
```

## Phase 3 — Recreate roles, restore data

Copy the dump file from home PC to VPS:

```powershell
# On home PC (PowerShell):
scp C:\Project\trading_db.dump vps-user@vps-ip:/tmp/trading_db.dump
```

On the VPS, create the two custom roles with the new strong passwords
**before** restoring (so the dump's role-aware permission GRANTs land
on actual roles):

```bash
docker exec -it blackheart-postgres psql -U postgres -d trading_db <<SQL
CREATE ROLE blackheart_trading  WITH LOGIN PASSWORD '<paste BLACKHEART_TRADING_PASS>';
CREATE ROLE blackheart_research WITH LOGIN PASSWORD '<paste BLACKHEART_RESEARCH_PASS>';
SQL
```

Move the dump into the container and restore:

```bash
docker cp /tmp/trading_db.dump blackheart-postgres:/tmp/trading_db.dump

# -j 4 parallelises across 4 worker processes — adjust to ≤ vCPU count.
docker exec blackheart-postgres pg_restore \
  -U postgres -d trading_db \
  --no-owner --no-acl \
  --jobs=4 \
  /tmp/trading_db.dump
```

Watch for errors. Expect a few harmless warnings like
`already exists, skipping` if the dump contains a public schema. **Any
ERROR line that mentions a table, foreign key, or index is real** —
stop and triage.

Grant the application roles their permissions on the restored schema:

```bash
docker exec -it blackheart-postgres psql -U postgres -d trading_db <<SQL
-- Trading role: full RW on application tables, no DDL.
GRANT CONNECT ON DATABASE trading_db TO blackheart_trading;
GRANT USAGE ON SCHEMA public TO blackheart_trading;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO blackheart_trading;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO blackheart_trading;

-- Research role: same, but typically read-heavy. If your local DB had
-- tighter restrictions, mirror them here. (See research/DB_USER_SEPARATION.md
-- for the canonical role-permission split.)
GRANT CONNECT ON DATABASE trading_db TO blackheart_research;
GRANT USAGE ON SCHEMA public TO blackheart_research;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO blackheart_research;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO blackheart_research;
SQL
```

## Phase 4 — Verify the restore is byte-faithful

Side-by-side row counts on critical tables:

```bash
# Run BOTH commands and compare row counts visually.

# Home PC (source):
docker exec blackheart-postgres psql -U postgres -d trading_db -c "
SELECT
  (SELECT count(*) FROM market_data)        AS market_data,
  (SELECT count(*) FROM feature_store)      AS feature_store,
  (SELECT count(*) FROM backtest_run)       AS backtest_run,
  (SELECT count(*) FROM backtest_trade)     AS backtest_trade,
  (SELECT count(*) FROM account_strategy)   AS account_strategy,
  (SELECT count(*) FROM trades)             AS trades,
  (SELECT count(*) FROM flyway_schema_history) AS flyway_rows;
"

# VPS (destination, via the same command on the VPS):
docker exec blackheart-postgres psql -U postgres -d trading_db -c "
SELECT
  (SELECT count(*) FROM market_data)        AS market_data,
  ... (same query)
"
```

The numbers must match exactly. If they don't, the restore is
incomplete — check `pg_restore` output for ERROR lines.

Also confirm Flyway head:

```bash
docker exec blackheart-postgres psql -U postgres -d trading_db -c \
  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# Expect V58 success=t at the top.
```

## Phase 5 — Reconfigure the trading JVM (on VPS)

Update the trading JVM's startup environment on the VPS:

```bash
# Wherever you wire env vars on the VPS (systemd unit, docker-compose,
# .env, shell script):

# Postgres now lives on the VPS itself — connect to localhost.
export DB_URL='jdbc:postgresql://localhost:5432/trading_db'
export DB_USERNAME='blackheart_trading'
export DB_PASSWORD='<paste BLACKHEART_TRADING_PASS>'

# JWT — same value on both JVMs (so cookies issued by trading verify on research).
export JWT_SECRET='<paste rand-base64 64 value>'

# Research-proxy upstream (from the Tailscale doc).
export APP_RESEARCH_BASE_URL='http://<TAILSCALE_HOME>:8081'
export APP_RESEARCH_ORCHESTRATOR_BASE_URL='http://<TAILSCALE_HOME>:8082'
```

Boot the trading JVM. Watch the logs:

```
INFO  org.flywaydb.core.FlywayExecutor : Database: jdbc:postgresql://localhost:5432/trading_db
INFO  org.flywaydb.core.internal.command.DbValidate : Successfully validated 58 migrations
INFO  ... Started BlackheartApplication ...
```

Two things to verify:

1. **No checksum mismatches.** Flyway sees V58 already applied, validates
   each migration's checksum against the SQL file shipped in the JAR.
   If mismatched, see the V56-checksum-repair pattern in your session
   memory (it's a one-row `UPDATE flyway_schema_history` against the
   right checksum value).
2. **No `Missing column` Hibernate validation errors.** Means the dump
   was complete and the schema is in sync with the entity classes.

## Phase 6 — Reconfigure the research JVM (on home PC)

```powershell
# On home PC, in the research JVM startup environment:
$env:DB_URL      = 'jdbc:postgresql://<TAILSCALE_VPS_IP>:5432/trading_db'
$env:DB_USERNAME = 'blackheart_research'
$env:DB_PASSWORD = '<paste BLACKHEART_RESEARCH_PASS>'
$env:JWT_SECRET  = '<same value as on VPS>'
$env:RESEARCH_BIND_ADDRESS = '<TAILSCALE_HOME_IP>'   # see DEPLOYMENT_TAILSCALE.md
```

Boot research JVM. Watch for the same "started successfully" log line.
Expect `:8081 /actuator/health` to return UP when called via Tailscale.

## Phase 7 — End-to-end smoke test

From your laptop (anywhere on the public internet):

```bash
# Hit the trading JVM via your VPS's public hostname:
curl -sS https://yourdomain.com/actuator/health
# → {"status":"UP"}

# Auth — log in and capture the cookie:
curl -sS -c jar.txt -X POST https://yourdomain.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"...","password":"..."}'

# Read backtests (proxied to research JVM over Tailscale, then to VPS DB):
curl -sS -b jar.txt https://yourdomain.com/api/v1/backtest | head -200
# → JSON list matching what was in your local DB.

# Submit a tiny new backtest — confirms research-side writes work.
curl -sS -b jar.txt -X POST https://yourdomain.com/api/v1/backtest \
  -H 'Content-Type: application/json' \
  -d '{ ... small payload ... }'
# → 201 with new backtest_run id.

# Confirm trading-side writes work — easiest is to flip a strategy
# enable/disable and watch the row update on the VPS DB:
curl -sS -b jar.txt -X POST https://yourdomain.com/api/v1/account-strategies/<id>/deactivate
docker exec blackheart-postgres psql -U postgres -d trading_db -c \
  "SELECT account_strategy_id, enabled, updated_time FROM account_strategy WHERE account_strategy_id='<id>';"
# → enabled=false, updated_time within the last few seconds.
```

If all three writes succeed and reads come back with the right shape,
the migration is done.

## Phase 8 — Keep the local DB as a hot rollback

**Do not delete the local Docker volume immediately.** Keep it around
for ~1 week:

```bash
# On home PC — stop the container but PRESERVE the volume.
docker stop blackheart-postgres
# DO NOT run `docker rm` or `docker volume rm`.
```

If something goes wrong on the VPS (slow query patterns, data corruption,
Tailscale stability issue), rollback is:

```bash
# Home PC:
docker start blackheart-postgres

# Revert trading JVM env vars on VPS to point at home PC's DB via Tailscale:
export DB_URL='jdbc:postgresql://<TAILSCALE_HOME_IP>:5432/trading_db'
# (this is the "Option A" topology from the Tailscale doc — slower but works)
```

After a week of stable operation, you can archive the local volume:

```bash
# One-time archive to external storage (in case you ever want the raw
# point-in-time-of-migration data back):
docker run --rm -v blackheart-postgres_pgdata:/data -v $(pwd):/backup \
  alpine tar czf /backup/pgdata-pre-migration.tgz -C /data .

# Then drop the volume:
docker rm blackheart-postgres
docker volume rm blackheart-postgres_pgdata
```

## Operational notes

- **Postgres listens on Tailscale**: `0.0.0.0` bind inside the container
  is fine *because the host's port mapping only exposes 5432 on the
  Tailscale interface*. Double-check with `ss -tlnp | grep 5432` on the
  VPS — you should only see Tailscale and localhost, never `0.0.0.0`.
- **Backups going forward**: schedule `pg_dump -Fc` to a free S3-compatible
  bucket (Backblaze B2, Cloudflare R2 — both have free tiers). One cron
  on the VPS. Don't rely on Docker volume snapshots; they're not crash-
  consistent.
- **Connection pooling**: Hibernate's default pool is plenty for two
  JVMs against this workload. Don't add PgBouncer unless metrics tell
  you to.
- **Don't run V59+** migrations until you've verified V58 round-trip
  works. Migrations on a freshly-restored Postgres are the riskiest
  failure mode.
