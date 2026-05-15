# Blackheart deploy/ — Production deployment artifacts

Reference configuration for running Blackheart as two isolated services on
a Linux production host. See [`../research/DEPLOYMENT.md`](../research/DEPLOYMENT.md)
for the full migration runbook; this README covers operator install steps.

## Layout

```
deploy/
├── README.md                  # this file
├── systemd/
│   ├── blackheart-trading.service     # live trading JVM (port 8080)
│   └── blackheart-research.service    # research JVM (port 8081)
└── logrotate/
    └── blackheart                     # log rotation rules
```

## One-time host setup

```bash
# Create least-privilege users (no shell, no sudo)
sudo useradd -r -s /sbin/nologin -d /opt/blackheart blackheart
sudo useradd -r -s /sbin/nologin -d /opt/blackheart blackheart-research

# Install directories
sudo mkdir -p /opt/blackheart/{logs,data,research}
sudo chown blackheart:blackheart /opt/blackheart
sudo chown blackheart-research:blackheart-research /opt/blackheart/research

# Credentials. NEVER commit these. mode 600 keeps them readable only by
# the systemd unit's user via EnvironmentFile= directive.
sudo mkdir -p /etc/blackheart
sudo install -m 600 /dev/null /etc/blackheart/trading.env
sudo install -m 600 /dev/null /etc/blackheart/research.env

# Edit the env files. Required keys:
#   trading.env:
#     DB_URL=jdbc:postgresql://localhost:5432/trading_db
#     DB_USERNAME=blackheart_trading      # V14 role, NOT postgres
#     DB_PASSWORD=<set after V14 ALTER ROLE>
#     JWT_SECRET=<JwtSecretGenerator output>
#     BLACKHEART_DEV_AUTH_DISABLED=true   # phase-1 kill-switch
#
#   research.env:
#     DB_URL=jdbc:postgresql://localhost:5432/trading_db
#     DB_USERNAME=blackheart_research     # V14 role
#     DB_PASSWORD=<set after V14 ALTER ROLE>
#     BLACKHEART_DEV_EMAIL=<orchestrator account email>
#     BLACKHEART_DEV_PASSWORD=<orchestrator account password>
sudo $EDITOR /etc/blackheart/trading.env
sudo $EDITOR /etc/blackheart/research.env
```

## Install services

```bash
sudo cp deploy/systemd/blackheart-trading.service /etc/systemd/system/
sudo cp deploy/systemd/blackheart-research.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable blackheart-trading blackheart-research
```

## Initial start

Order matters — start trading first, verify, then research.

```bash
# 1. Trading
sudo systemctl start blackheart-trading
sudo systemctl status blackheart-trading
curl -fsS http://localhost:8080/healthcheck    # expect 200

# 2. Research (only after trading is healthy)
sudo systemctl start blackheart-research
sudo systemctl status blackheart-research
curl -fsS http://localhost:8081/healthcheck    # expect 200
```

## Updating a binary

DO NOT use `systemctl restart` for binary upgrades — that kills + relaunches
in one step; if the new JAR fails to boot, the old process is already gone.
Use stop / swap / start instead, so you can fall back.

```bash
# Stage the new JAR
sudo install -m 644 -o blackheart -g blackheart \
    build/libs/blackheart-trading-X.Y.Z.jar \
    /opt/blackheart/blackheart-trading-X.Y.Z.jar

# Stop the old one
sudo systemctl stop blackheart-trading

# Swap the symlink (atomic)
sudo ln -sf blackheart-trading-X.Y.Z.jar /opt/blackheart/blackheart-trading.jar

# Start the new one
sudo systemctl start blackheart-trading

# Verify
curl -fsS http://localhost:8080/healthcheck
journalctl -u blackheart-trading --since "1 minute ago"

# Roll back if broken
sudo systemctl stop blackheart-trading
sudo ln -sf blackheart-trading-PREVIOUS.jar /opt/blackheart/blackheart-trading.jar
sudo systemctl start blackheart-trading
```

The research JAR follows the same pattern but with lower urgency — research
restarts are non-destructive.

## Resource budget summary

These come from the systemd unit `MemoryMax`, `CPUQuota`, etc. directives.

| Service | Heap (-Xmx) | OS MemoryMax | CPUQuota | IO class | Nice |
|---|---|---|---|---|---|
| `blackheart-trading` | 2 GB | 3 GB | 300% (3 cores) | realtime/4 | -5 |
| `blackheart-research` | 1.5 GB | 2 GB | 200% (2 cores) | best-effort/6 | 10 |

Total budget on the host: 5 GB RAM (excluding Postgres, Redis, OS), 5 cores
under saturation. Sized for an 8 GB / 4-core node; double everything on a
larger box.

## Log rotation

```bash
sudo cp deploy/logrotate/blackheart /etc/logrotate.d/blackheart
sudo logrotate -d /etc/logrotate.d/blackheart    # dry-run
```

systemd-managed stdout/stderr goes through journald — set retention via
`/etc/systemd/journald.conf`:

```ini
[Journal]
SystemMaxUse=2G
MaxRetentionSec=8w
```

Then:

```bash
sudo systemctl restart systemd-journald
```

## Health monitoring (operator cheat sheet)

```bash
# Both services up?
systemctl is-active blackheart-trading blackheart-research

# Trading restart count (should be 0 in steady state)
systemctl show -p NRestarts blackheart-trading

# Recent errors
journalctl -u blackheart-trading --since "1 hour ago" -p err

# Memory pressure on the JVM
sudo -u blackheart jcmd $(pgrep -u blackheart -f BlackheartApplication) GC.heap_info
```

## Windows operator notes

The Windows equivalent of this layout uses Task Scheduler (already wired
via `research/scripts/install-windows-cron.ps1`) plus per-process resource
priority. The systemd `MemoryMax` / `CPUQuota` features have no direct
Windows equivalent at the Task Scheduler layer — for Windows production
deployments, consider:

- Run trading as a Windows Service via `nssm` or `winsw` for restart
  semantics equivalent to `Restart=on-failure`.
- Use Windows Job Objects (`SetInformationJobObject`) to apply memory caps
  per process — wrap the JVM launch in a small C# helper, or use
  `procgov` (https://github.com/lowleveldesign/process-governor).
- Or, more pragmatically: deploy production on Linux. Windows is fine for
  dev / single-operator local research; for a real production posture,
  the systemd story above is significantly cleaner.
