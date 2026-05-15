# Tailscale-mesh deployment — VPS (trading + frontend) ↔ home PC (research)

> Two machines on a private Tailscale mesh. VPS is publicly reachable on
> `:443`. Research JVM stays bound to the home PC, never the open
> internet. The trading JVM's existing `ResearchProxyController`
> forwards research traffic over Tailscale.

## Topology

```
┌─────────────────────────────────────────────────────────────┐
│  Public internet                                            │
│   ↓ HTTPS                                                   │
│  VPS  (Tailscale IP: 100.x.x.A)                             │
│   - Trading JVM      :8080  (Binance, auth, live trades)    │
│   - Frontend         :3000  (Next.js, Blackridge)           │
│   - Caddy / Nginx    :443   (HTTPS termination + reverse    │
│                              proxy → 8080 / 3000)            │
│   - Postgres         :5432  (if moved to VPS — see "DB      │
│                              location decision" below)       │
└───────────────────────────────┬─────────────────────────────┘
                                │ Tailscale (encrypted P2P,
                                │  no inbound ports on home
                                │  router needed)
                                │
┌───────────────────────────────▼─────────────────────────────┐
│  Home PC  (Tailscale IP: 100.x.x.B)                          │
│   - Research JVM     :8081  (bound to Tailscale IP only)    │
│   - Research orch.   :8082  (bound to 127.0.0.1 — only the  │
│                              research JVM calls it; trading │
│                              JVM proxies through research)  │
│   - Postgres         :5432  (if kept here — see below)       │
└─────────────────────────────────────────────────────────────┘
```

The frontend, Blackheart trading JVM, and any public traffic only touch
the VPS. The research JVM and research orchestrator are unreachable from
the public internet; only devices on your tailnet can reach them.

## DB location decision (do this first)

You must decide where Postgres lives before installing anything. Two
options:

| Option | DB on | Hot path                        | Cold path                       | Pros | Cons |
|---|---|---|---|---|---|
| **A** | Home PC | Trading JVM → Tailscale → DB    | Research JVM → localhost → DB   | No data migration | Every live order writes cross the Tailscale link (~5–30ms RTT added per query) |
| **B** | VPS     | Trading JVM → localhost → DB    | Research JVM → Tailscale → DB   | Live trading stays low-latency | One-time `pg_dump` + restore to move the data |

**Recommendation: B.** Trading has hard latency budgets (signal → fill
window); research doesn't. Bursty backtest reads tolerate Tailscale RTT;
hot-path order writes do not.

If you pick B, the one-time data migration is:

```bash
# On home PC:
docker exec blackheart-postgres pg_dump -U postgres -Fc trading_db > trading_db.dump

# scp to VPS:
scp trading_db.dump vps-user@vps-ip:/tmp/

# On VPS (after postgres is installed there):
docker cp /tmp/trading_db.dump <vps-postgres-container>:/tmp/
docker exec <vps-postgres-container> pg_restore -U postgres -d trading_db /tmp/trading_db.dump
```

If you pick A, leave the data where it is. The DB just becomes another
Tailscale-reachable service for the VPS-side trading JVM.

## Step 1 — Install Tailscale on both machines

### VPS (Linux, e.g. Ubuntu / Debian)

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
# Follow the printed URL on a desktop browser. Authenticate the VPS into your tailnet.
tailscale ip -4
# Note the IP — this is the VPS's Tailscale IP (call it TAILSCALE_VPS).
```

### Home PC (Windows)

1. Download from https://tailscale.com/download/windows
2. Install + run, sign in with the same account used for the VPS.
3. In PowerShell:

```powershell
tailscale ip -4
# Note the IP — call this TAILSCALE_HOME.
```

After this step, from the VPS you should be able to:

```bash
ping TAILSCALE_HOME      # works
curl http://TAILSCALE_HOME:8081/actuator/health
                          # works once research JVM is bound (Step 3)
```

If the ping fails, the tailnet didn't form — both machines may not be
signed into the same account. Re-run `tailscale up` and check
`tailscale status` on each.

## Step 2 — Lock down the tailnet (recommended)

Tailscale ACLs let you whitelist exactly which devices reach which
ports. Skip this initially to keep the setup simple; add it once
you're confirmed working. In the Tailscale admin console
(login.tailscale.com → Access Controls), the policy below restricts
the home PC to only accepting connections from the VPS on the relevant
ports:

```jsonc
{
  "acls": [
    // VPS reaches research JVM + orchestrator + postgres on home PC.
    {"action": "accept", "src": ["tag:vps"],  "dst": ["tag:home:8081,8082,5432"]},
    // You (any of your personal devices) can reach anything on home PC for debug.
    {"action": "accept", "src": ["autogroup:owner"], "dst": ["*:*"]}
  ],
  "tagOwners": {
    "tag:vps":  ["autogroup:owner"],
    "tag:home": ["autogroup:owner"]
  }
}
```

Tag each machine in the admin console (Machines → ⋯ → Edit ACL tags).

## Step 3 — Bind research JVM to Tailscale (home PC)

Open `application-research.properties` reference: it already supports
overriding the bind address via `RESEARCH_BIND_ADDRESS` env var. You do
NOT need to edit the file — just launch the research JVM with the env
var pointed at the Tailscale interface.

PowerShell launch:

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.1\jbr'
$env:RESEARCH_BIND_ADDRESS = '0.0.0.0'   # see note below — this is safe with Tailscale ACLs
# OR, stricter, bind to the Tailscale IP only:
# $env:RESEARCH_BIND_ADDRESS = '<TAILSCALE_HOME>'

# (If DB on home PC) DB env vars stay default. If DB on VPS:
$env:DB_URL  = 'jdbc:postgresql://<TAILSCALE_VPS>:5432/trading_db'

java -jar C:\Project\blackheart\build\libs\blackheart-research-0.0.1-SNAPSHOT.jar `
     --spring.profiles.active=dev,research --server.port=8081
```

**About `0.0.0.0` vs the Tailscale IP**:

- `0.0.0.0` binds to *all* local interfaces, including the public
  network adapter. **Without** Tailscale ACLs (Step 2) or Windows
  Firewall rules, anyone on your home LAN could reach port 8081.
- Setting it to the exact Tailscale IP scopes the listener to that
  interface only — bulletproof, even if your firewall is off.

If you skip the ACL step, **bind explicitly to the Tailscale IP**, not
`0.0.0.0`.

Also turn off the local Windows Firewall *inbound* rule for port 8081
that may have been opened earlier — research is only reachable via
Tailscale now.

## Step 4 — Point the trading JVM's research-proxy at the home PC (VPS)

The trading JVM proxies all `/api/v1/{backtest,research,montecarlo,historical}/**`
and `/research-actuator/**` to the research JVM. The upstream URL is
configurable; set it on the VPS:

```bash
# On VPS, in the trading-JVM startup environment (systemd unit / docker
# compose / .env file — wherever you wire env vars):
export APP_RESEARCH_BASE_URL=http://<TAILSCALE_HOME>:8081
export APP_RESEARCH_ORCHESTRATOR_BASE_URL=http://<TAILSCALE_HOME>:8082
```

Spring's relaxed binding maps these to the
`app.research.base-url` / `app.research.orchestrator.base-url`
properties (see `ResearchProxyController:85`,
`ResearchOrchestratorProxyController:57`).

After restart, hit `https://yourdomain.com/api/v1/backtest/<id>` from
the public internet — the trading JVM auth-checks the request, then
forwards it to the home PC over Tailscale. The home PC never sees a
public packet.

## Step 5 — Frontend env vars (Blackridge)

Blackridge has two relevant env vars:

```env
NEXT_PUBLIC_API_URL=https://yourdomain.com           # trading JVM (public, via Caddy)
NEXT_PUBLIC_RESEARCH_URL=https://yourdomain.com      # leave UNSET in production
NEXT_PUBLIC_WS_URL=wss://yourdomain.com/ws           # trading JVM websocket
```

Leave `NEXT_PUBLIC_RESEARCH_URL` **unset** (or equal to `NEXT_PUBLIC_API_URL`).
The frontend's `researchClient` falls back to the trading JVM, which in
turn proxies to research over Tailscale. Do NOT expose the research
JVM's port directly to the browser — the frontend's `researchClient`
sends auth cookies, and routing them at the research JVM would bypass
the proxy's CORS / origin-safety belt.

## Step 6 — One-time JWT_SECRET (do this before going public)

Both JVMs read `JWT_SECRET` env var. The dev default is in the repo, so
on the VPS **and** the home PC, set a real one. Generate once on the
VPS:

```bash
openssl rand -base64 64
```

Then set the **same value** as `JWT_SECRET` on both machines. Same
secret on both sides ensures tokens minted by the trading JVM verify on
the research JVM (since the proxy forwards the auth cookie).

## Step 7 — Verify

From the VPS shell:

```bash
curl http://<TAILSCALE_HOME>:8081/actuator/health
# → {"status":"UP"}

# Via the trading-JVM proxy (over public internet):
curl -H "Cookie: jwt=<your-token>" https://yourdomain.com/api/v1/backtest
# → list of your backtests (proxied to home PC, never publicly exposed)
```

From a non-tailnet device (e.g. your phone on cellular without
Tailscale installed):

```bash
curl http://<your-home-public-ip>:8081
# → connection refused (research never bound to public interface)
```

That's the success criterion: the research JVM only answers calls
arriving over Tailscale.

## Failure / rollback

To roll back the entire setup, revert env vars on the VPS:

```bash
unset APP_RESEARCH_BASE_URL APP_RESEARCH_ORCHESTRATOR_BASE_URL
```

The proxy falls back to `http://127.0.0.1:8081` and `:8082`, which
means trading JVM expects research running on its own loopback (the
pre-Tailscale single-host topology). Useful for a local dev environment
that doesn't have Tailscale installed.

## Operational notes

- **Tailscale auto-reconnects** on network changes (laptop sleep, home
  router reboot, VPS reboot). No manual intervention needed.
- **Tailscale IPs are stable** per-device until you remove the device
  from the tailnet. Hardcode them in env vars; don't rely on a service
  discovery layer.
- **MagicDNS** (Tailscale → DNS in admin console) lets you replace IPs
  with names: `http://home-pc:8081` instead of `http://100.x.x.B:8081`.
  Optional, nice for readability.
- **Latency** between VPS and home PC depends on Tailscale's NAT
  traversal. After the initial handshake, traffic goes peer-to-peer; if
  P2P fails (rare, only behind very strict CGNAT), it falls back to
  Tailscale's DERP relays at higher latency. Check with
  `tailscale ping <other-machine>`.
- **Free tier limit**: 100 devices, 3 users. You'll use 2–3 devices.
  No bandwidth cap.
