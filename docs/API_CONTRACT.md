# Blackheart — Backend API Contract

> **Source of truth** between the frontend (Next.js) and Spring Boot backend.
>
> **Base URL** `http://localhost:8080` (`NEXT_PUBLIC_API_URL`)
> **WebSocket URL** `ws://localhost:8080/ws` (`NEXT_PUBLIC_WS_URL`)
> **Auth** `Authorization: Bearer <token>` on every protected request.

---

## Implementation Status Legend

| Symbol | Meaning |
|---|---|
| ✅ | Implemented and path matches contract |
| ⚠️ | Implemented but **path or field names differ** — needs alignment |
| ❌ | Not implemented yet |

---

## 0. Response Envelope

All REST responses use `ResponseDto`:

```json
{
  "responseCode": "20000",
  "responseDesc": "Success",
  "data": {},
  "errorMessage": null
}
```

| Field | Type | Notes |
|---|---|---|
| `responseCode` | `string` | `"20000"` success · `"4xxxx"` client error · `"5xxxx"` server error |
| `responseDesc` | `string` | Human-readable status |
| `data` | `T \| null` | Payload; `null` for void responses |
| `errorMessage` | `string \| null` | Non-null only on error |

**Pagination envelope** (when `data` is a list):
```json
{ "content": [], "page": 0, "size": 20, "total": 100 }
```

---

## 1. Authentication

### ✅ POST `/api/v1/users/login`
Public.

**Request**
```json
{ "email": "user@example.com", "password": "password123" }
```

**Response `data`**
```json
{
  "accessToken": "eyJhbGci...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": {
    "userId": "21ebe410-9dcf-464e-9419-fabed59aef56",
    "email": "user@example.com",
    "fullName": "System Administrator",
    "phoneNumber": null,
    "role": "ADMIN",
    "status": "ACTIVE",
    "emailVerified": false,
    "lastLoginAt": "2026-04-19T17:09:37.997024",
    "createdTime": "2026-04-18T08:53:15.526678",
    "updatedTime": "2026-04-19T17:09:37.819075"
  }
}
```

---

### ✅ POST `/api/v1/users/register`
Public.

**Request**
```json
{ "email": "user@example.com", "password": "password123", "fullName": "Full Name" }
```

**Response `data`** — same shape as login (`accessToken` + `user`).

---

### ✅ GET `/api/v1/users/me`
Protected.

**Response `data`** — `UserResponse` object:
```json
{
  "userId": "21ebe410-...",
  "email": "user@example.com",
  "fullName": "System Administrator",
  "phoneNumber": null,
  "role": "ADMIN",
  "status": "ACTIVE",
  "emailVerified": false,
  "lastLoginAt": "2026-04-19T17:09:37.997024",
  "createdTime": "2026-04-18T08:53:15.526678",
  "updatedTime": "2026-04-19T17:09:37.819075"
}
```

---

### ✅ PATCH `/api/v1/users/me`
Protected. Partial profile update.

**Request** — partial `UserResponse` fields.

---

## 2. Trades

### ❌ GET `/api/v1/trades`
Protected. Query trades for the authenticated user.

> **Current backend:** Only `/api/v1/trades/account/{accountId}/active` exists.
> A unified endpoint with query params is needed.

**Query parameters**

| Param | Type | Required | Description |
|---|---|---|---|
| `status` | `OPEN \| PARTIALLY_CLOSED \| CLOSED` | No | Filter by status |
| `accountId` | `UUID` | No | Filter by account |
| `limit` | `number` | No | Max results |
| `page` | `number` | No | Zero-based page |
| `size` | `number` | No | Page size |

**Response `data`** — array or paginated `Trade[]`:
```json
[
  {
    "id": "uuid",
    "accountId": "uuid",
    "accountStrategyId": "uuid",
    "strategyCode": "LSR_V2",
    "symbol": "BTCUSDT",
    "direction": "LONG",
    "status": "CLOSED",
    "entryTime": 1713456000000,
    "entryPrice": 62450.50,
    "exitTime": 1713542400000,
    "exitAvgPrice": 63100.00,
    "stopLossPrice": 61500.00,
    "tp1Price": 63200.00,
    "tp2Price": 64000.00,
    "quantity": 0.05,
    "realizedPnl": 32.48,
    "unrealizedPnl": 0.0,
    "feeUsdt": 1.24,
    "markPrice": null,
    "unrealizedPnlPct": null,
    "positions": []
  }
]
```

For open trades, `markPrice`, `unrealizedPnl`, `unrealizedPnlPct` must be populated.

---

### ❌ GET `/api/v1/trades/:id`
Protected. Single trade with nested positions.

**Response `data`**
```json
{
  "id": "uuid",
  "positions": [
    {
      "id": "uuid",
      "tradeId": "uuid",
      "type": "TP1",
      "quantity": 0.025,
      "entryPrice": 62450.50,
      "exitTime": 1713500000000,
      "exitPrice": 63200.00,
      "exitReason": "TP_HIT",
      "feeUsdt": 0.62,
      "realizedPnl": 18.74
    }
  ]
}
```

**TradePosition `type`** values: `SINGLE`, `TP1`, `TP2`, `RUNNER`
**TradePosition `exitReason`** values: `TP_HIT`, `SL_HIT`, `RUNNER_CLOSE`, `MANUAL_CLOSE`, `BACKTEST_END`

---

## 3. P&L

### ❌ GET `/api/v1/pnl/summary`
Protected.

> **Current backend:** Only `/api/v1/trades/account/{accountId}/active-pnl` exists, returning unrealized P&L only.
> A summary endpoint with period aggregation is needed.

**Query parameters**

| Param | Type | Required | Values |
|---|---|---|---|
| `period` | `string` | Yes | `today`, `week`, `month` |

**Response `data`**
```json
{
  "period": "today",
  "realizedPnl": 145.32,
  "unrealizedPnl": 32.48,
  "totalPnl": 177.80,
  "tradeCount": 8,
  "winRate": 62.5,
  "openCount": 2
}
```

---

### ❌ GET `/api/v1/pnl/daily`
Protected.

**Query parameters**

| Param | Type | Required |
|---|---|---|
| `from` | `ISO8601 date` | Yes |
| `to` | `ISO8601 date` | Yes |
| `strategyCode` | `string` | No |

**Response `data`**
```json
[
  { "date": "2026-04-18", "realizedPnl": 85.20, "tradeCount": 4 },
  { "date": "2026-04-19", "realizedPnl": 145.32, "tradeCount": 8 }
]
```

---

### ❌ GET `/api/v1/pnl/by-strategy`
Protected.

**Query parameters** — `from`, `to` (ISO8601 dates, optional)

**Response `data`**
```json
[
  { "strategyCode": "LSR_V2", "realizedPnl": 210.50, "tradeCount": 12, "winRate": 66.7 }
]
```

---

## 4. Account Strategies

### ✅ GET `/api/v1/account-strategies`
Protected. All strategies for the authenticated user.

**Response `data`** — `AccountStrategyResponse[]`

> **Field name gap:** Backend returns `accountStrategyId` but frontend may expect `id`.
> Backend returns `intervalName` but frontend expects `interval`.
> Backend returns `currentStatus` but frontend expects `status`.
> Backend returns `createdTime`/`updatedTime` but frontend expects `createdAt`/`updatedAt`.

```json
[
  {
    "id": "uuid",
    "accountId": "uuid",
    "strategyCode": "LSR_V2",
    "symbol": "BTCUSDT",
    "interval": "1h",
    "status": "LIVE",
    "capitalAllocatedUsdt": 1000.00,
    "allowLong": true,
    "allowShort": false,
    "priorityOrder": 1,
    "createdAt": "2026-04-18T08:53:15.526678",
    "updatedAt": "2026-04-19T17:09:37.819075"
  }
]
```

`status` values: `LIVE`, `PAUSED`, `STOPPED`

---

### ✅ GET `/api/v1/account-strategies/account/:accountId`
Protected. Strategies for a specific account.

---

## 5. Strategy Parameters — LSR

### ✅ GET `/api/v1/lsr-params/defaults`
Protected. System defaults.

**Response `data`** — `LsrParams` with all fields at defaults.

---

### ✅ GET `/api/v1/lsr-params/:accountStrategyId`
Protected.

**Response `data`** — `LsrParamResponse`:
```json
{
  "accountStrategyId": "uuid",
  "hasCustomParams": true,
  "overrides": { "adxThreshold": 30 },
  "effectiveParams": {
    "adxThreshold": 30,
    "rsiOverbought": 70,
    "rsiOversold": 30,
    "stopLossAtr": 2.0,
    "tp1RMultiple": 1.0,
    "tp2RMultiple": 2.0,
    "useRunner": true,
    "riskPercentage": 1.0,
    "maxPositionSizeUsdt": 500.0
  },
  "version": 1,
  "updatedAt": "2026-04-19T17:00:00"
}
```

---

### ✅ PUT `/api/v1/lsr-params/:accountStrategyId`
Protected. Full replace.

**Request** — full `LsrParams` object.

---

### ✅ PATCH `/api/v1/lsr-params/:accountStrategyId`
Protected. Partial update.

**Request** — partial `LsrParams` (only fields to change).

---

### ✅ DELETE `/api/v1/lsr-params/:accountStrategyId`
Protected. Removes custom overrides (reverts to defaults).

---

## 6. Strategy Parameters — VCB

### ✅ GET `/api/v1/vcb-params/defaults`
### ✅ GET `/api/v1/vcb-params/:accountStrategyId`
### ✅ PUT `/api/v1/vcb-params/:accountStrategyId`
### ✅ PATCH `/api/v1/vcb-params/:accountStrategyId`
### ✅ DELETE `/api/v1/vcb-params/:accountStrategyId`

Same structure as LSR params. Response `data` is `VcbParamResponse` with `effectiveParams` as `VcbParams`:
```json
{
  "compressionLookback": 20,
  "compressionBbWidth": 0.03,
  "compressionKcWidth": 0.025,
  "useKcFilter": true,
  "minBreakoutAtr": 0.5,
  "maxBreakoutAtr": 3.0,
  "volumeMultiplier": 1.5,
  "useVolumeFilter": true,
  "stopLossAtr": 1.5,
  "atrPeriod": 14,
  "tp1RMultiple": 1.0,
  "tp2RMultiple": 2.0,
  "useRunner": false,
  "riskPercentage": 1.0,
  "maxPositionSizeUsdt": 500.0
}
```

---

## 7. Backtest

### ⚠️ POST `/api/v1/backtest`
Protected.

> **Current backend:** `POST /api/backtest/run` — path needs updating to `/api/v1/backtest`.
> Request body also differs from the frontend contract below.

**Request**
```json
{
  "symbol": "BTCUSDT",
  "interval": "1h",
  "fromDate": "2024-01-01",
  "toDate": "2024-06-30",
  "initialCapital": 10000.0,
  "strategyCode": "LSR_V2",
  "strategyAccountStrategyIds": { "LSR_V2": "account-strategy-uuid" },
  "strategyParamOverrides": {
    "LSR_V2": { "adxThreshold": 30, "stopLossAtr": 1.5 }
  }
}
```

`strategyCode` can be comma-separated for multi-strategy: `"LSR_V2,VCB"`

**Response `data`** — `BacktestRun`:
```json
{
  "id": "uuid",
  "userId": "uuid",
  "status": "PENDING",
  "symbol": "BTCUSDT",
  "interval": "1h",
  "fromDate": "2024-01-01",
  "toDate": "2024-06-30",
  "initialCapital": 10000.0,
  "strategyCode": "LSR_V2",
  "strategyAccountStrategyIds": { "LSR_V2": "uuid" },
  "paramSnapshot": { "LSR_V2": { "adxThreshold": 30 } },
  "metrics": null,
  "errorMessage": null,
  "createdAt": "2026-04-19T17:00:00",
  "completedAt": null
}
```

---

### ❌ GET `/api/v1/backtest`
Protected. Paginated list of runs.

**Query parameters** — `page`, `size` (optional)

**Response `data`** — paginated `BacktestRun[]` (same shape as POST response, `metrics` populated once complete).

---

### ❌ GET `/api/v1/backtest/:id`
Protected. Single run with full metrics.

**Response `data`**
```json
{
  "id": "uuid",
  "status": "COMPLETE",
  "metrics": {
    "totalReturn": 1250.50,
    "totalReturnPct": 12.51,
    "winRate": 58.3,
    "profitFactor": 1.82,
    "avgWinUsdt": 45.20,
    "avgLossUsdt": 24.80,
    "maxDrawdown": 380.00,
    "maxDrawdownPct": 3.8,
    "sharpe": 1.45,
    "sortino": 2.10,
    "totalTrades": 48,
    "winningTrades": 28,
    "losingTrades": 20
  },
  "paramSnapshot": { "LSR_V2": { "adxThreshold": 30 } }
}
```

---

### ❌ GET `/api/v1/backtest/:id/equity-points`
Protected. Time-series equity curve.

**Response `data`** — all timestamps epoch milliseconds:
```json
[
  { "ts": 1704067200000, "equity": 10000.00, "drawdown": 0.0, "drawdownPct": 0.0 },
  { "ts": 1704153600000, "equity": 10085.30, "drawdown": 0.0, "drawdownPct": 0.0 }
]
```

---

### ❌ GET `/api/v1/backtest/:id/trades`
Protected. All trades in this backtest run with nested position legs.

**Response `data`**
```json
[
  {
    "id": "uuid",
    "backtestRunId": "uuid",
    "direction": "LONG",
    "entryTime": 1704067200000,
    "entryPrice": 42500.00,
    "exitTime": 1704153600000,
    "exitPrice": 43200.00,
    "stopLossPrice": 41800.00,
    "tp1Price": 43200.00,
    "tp2Price": 44000.00,
    "quantity": 0.1,
    "realizedPnl": 70.00,
    "rMultiple": 1.0,
    "positions": [
      {
        "id": "uuid",
        "type": "TP1",
        "quantity": 0.05,
        "exitTime": 1704153600000,
        "exitPrice": 43200.00,
        "exitReason": "TP_HIT",
        "realizedPnl": 35.00
      }
    ]
  }
]
```

---

### ❌ GET `/api/v1/backtest/:id/candles`
Protected. OHLCV data for the backtest symbol/interval/date range.

**Response `data`** — all timestamps epoch milliseconds:
```json
[
  {
    "symbol": "BTCUSDT",
    "interval": "1h",
    "openTime": 1704067200000,
    "open": 42480.00,
    "high": 42650.00,
    "low": 42310.00,
    "close": 42500.00,
    "volume": 1250.5,
    "closeTime": 1704070799999
  }
]
```

---

## 8. Market Data

### ❌ GET `/api/v1/market`
Protected. Historical OHLCV candles.

> **Current backend:** Only `/api/v1/market/latest-price/{symbol}` exists.
> A full OHLCV query endpoint is needed.

**Query parameters**

| Param | Type | Required |
|---|---|---|
| `symbol` | `string` | Yes |
| `interval` | `1m \| 5m \| 15m \| 1h \| 4h \| 1d` | Yes |
| `from` | `ISO8601` | No |
| `to` | `ISO8601` | No |
| `limit` | `number` | No |

**Response `data`** — `MarketData[]` (same shape as backtest candles above).

---

### 🔵 GET `/api/v1/market/indicators`
Protected. FeatureStore indicators for chart overlays.

**Query parameters** — `symbol`, `interval`

**Response `data`**
```json
[
  {
    "symbol": "BTCUSDT",
    "interval": "1h",
    "ts": 1704067200000,
    "emaFast": 42480.00,
    "emaSlow": 42200.00,
    "rsi": 58.3,
    "adx": 28.5,
    "atr": 650.0,
    "bbUpper": 43500.00,
    "bbMiddle": 42500.00,
    "bbLower": 41500.00,
    "kcUpper": 43200.00,
    "kcMiddle": 42500.00,
    "kcLower": 41800.00
  }
]
```

---

## 9. Portfolio

### ❌ GET `/api/v1/portfolio`
Protected.

> **Current backend:** `GET /v1/portofolio/reload` triggers an async reload and returns a string.
> A synchronous balance read endpoint is needed.

**Response `data`**
```json
{
  "accountId": "uuid",
  "totalUsdt": 12450.80,
  "availableUsdt": 10450.80,
  "lockedUsdt": 2000.00,
  "assets": [
    { "asset": "BTC", "free": 0.05, "locked": 0.0, "usdtValue": 3150.00 },
    { "asset": "USDT", "free": 10450.80, "locked": 2000.00, "usdtValue": 12450.80 }
  ]
}
```

---

## 10. Scheduler

### ❌ GET `/api/v1/scheduler`
Protected. Current scheduler state.

> **Current backend:** `POST /v1/scheduler/start` and `POST /v1/scheduler/stop/{schedulerId}` exist.
> A GET status endpoint and pause/resume endpoints are needed.

**Response `data`**
```json
{ "running": true, "activeStrategies": 2, "lastTickAt": "2026-04-19T17:09:37.819075" }
```

---

### ❌ POST `/api/v1/scheduler/pause`
Protected. Pause a strategy or all strategies.

**Request**
```json
{ "accountStrategyId": "uuid" }
```
Omit `accountStrategyId` to pause all.

---

### ❌ POST `/api/v1/scheduler/resume`
Protected. Same body shape as pause.

---

## 11. Monte Carlo

### ⚠️ POST `/api/v1/montecarlo`
Protected.

> **Current backend:** `POST /api/monte-carlo/run` — path needs updating to `/api/v1/montecarlo`.

**Request**
```json
{
  "backtestRunId": "uuid",
  "initialCapital": 10000.0,
  "simulationMode": "FIXED_TRADES",
  "numberOfSimulations": 1000,
  "horizonTrades": 100,
  "confidenceLevels": [0.05, 0.25, 0.5, 0.75, 0.95],
  "ruinThresholdPct": 50.0,
  "maxAcceptableDrawdownPct": 20.0,
  "randomSeed": null
}
```

**Response `data`** — `MonteCarloResponse` (comprehensive simulation results):
```json
{
  "monteCarloRunId": "uuid",
  "backtestRunId": "uuid",
  "numberOfSimulations": 1000,
  "tradesUsed": 48,
  "initialCapital": 10000.0,
  "meanFinalEquity": 11250.50,
  "medianFinalEquity": 11100.00,
  "minFinalEquity": 8500.00,
  "maxFinalEquity": 15000.00,
  "meanTotalReturnPct": 12.51,
  "medianTotalReturnPct": 11.00,
  "meanMaxDrawdownPct": 8.5,
  "worstMaxDrawdownPct": 35.0,
  "probabilityOfRuin": 0.02,
  "probabilityOfDrawdownBreach": 0.12,
  "probabilityOfProfit": 0.78,
  "finalEquityPercentiles": { "5": 9200.0, "25": 10500.0, "50": 11100.0, "75": 12000.0, "95": 14000.0 }
}
```

---

## 12. WebSocket — STOMP

**Endpoint** `ws://localhost:8080/ws`
**Protocol** STOMP over WebSocket
**Auth** Pass JWT in STOMP CONNECT frame:
```
CONNECT
Authorization:Bearer eyJhbGci...
```

### ✅ `/topic/sentiment/:symbol`
Real-time market sentiment updates (already implemented via `SentimentWebSocketController`).

### ❌ `/topic/pnl/:accountId`
Real-time P&L updates for all open trades of `accountId`.

> **Current backend:** `LivePnlWebSocketController` handles `@MessageMapping("/pnl.subscribe")`.
> The publish topic `/topic/pnl/{accountId}` needs to be verified/aligned.

**Message body**
```json
{
  "tradeId": "uuid",
  "accountId": "uuid",
  "markPrice": 63250.00,
  "unrealizedPnl": 40.00,
  "unrealizedPnlPct": 1.28,
  "ts": 1713542400000
}
```

---

## 13. CORS & Security Configuration

Current `SecurityConfig` is already aligned with frontend requirements:

```java
config.setAllowedOrigins(List.of("http://localhost:3000"));
config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
config.setAllowedHeaders(List.of("*"));
config.setAllowCredentials(true);
```

CSRF is disabled. STOMP WebSocket auth uses JWT in the CONNECT frame.

**Public endpoints** (no token required):
- `POST /api/v1/users/register`
- `POST /api/v1/users/login`
- `GET /healthcheck`
- `GET /ws`, `/ws/**`

---

## 14. Field Name Mapping (Backend ↔ Frontend)

Discrepancies that must be resolved (backend field → frontend expected field):

| Backend DTO | Backend field | Frontend expected | Endpoint |
|---|---|---|---|
| `UserResponse` | `userId` | `User.id` | login, register, /me |
| `UserResponse` | `fullName` | `User.name` | login, register, /me |
| `UserResponse` | `createdTime` | `User.createdAt` | login, register, /me |
| `LoginResponse` | `accessToken` | `LoginResponse.token` | login, register |
| `AccountStrategyResponse` | `accountStrategyId` | `id` | /account-strategies |
| `AccountStrategyResponse` | `intervalName` | `interval` | /account-strategies |
| `AccountStrategyResponse` | `currentStatus` | `status` | /account-strategies |
| `AccountStrategyResponse` | `createdTime` | `createdAt` | /account-strategies |
| `AccountStrategyResponse` | `updatedTime` | `updatedAt` | /account-strategies |

---

## 15. Endpoint Priority & Implementation Status

| Priority | Endpoint | Status | Controller |
|---|---|---|---|
| ✅ | `POST /api/v1/users/login` | **Live** | `UserController` |
| ✅ | `POST /api/v1/users/register` | **Live** | `UserController` |
| ✅ | `GET /api/v1/users/me` | **Live** | `UserController` |
| ✅ | `GET /api/v1/account-strategies` | **Live** (field name gaps — see §14) | `AccountStrategyController` |
| ✅ | `GET /api/v1/lsr-params/:id` + defaults + PUT/PATCH/DELETE | **Live** | `LsrStrategyParamController` |
| ✅ | `GET /api/v1/vcb-params/:id` + defaults + PUT/PATCH/DELETE | **Live** | `VcbStrategyParamController` |
| ✅ | `GET /api/v1/trades` | **Live** — unified query with status/accountId/page/size | `UnifiedTradeController` |
| ✅ | `GET /api/v1/trades/:id` | **Live** | `UnifiedTradeController` |
| ✅ | `GET /api/v1/pnl/summary` | **Live** | `PnlController` |
| ✅ | `GET /api/v1/pnl/daily` | **Live** | `PnlController` |
| ✅ | `GET /api/v1/pnl/by-strategy` | **Live** | `PnlController` |
| ✅ | `GET /api/v1/portfolio` | **Live** | `PortfolioController` |
| ✅ | `POST /api/v1/backtest` | **Live** | `BacktestV1Controller` |
| ✅ | `GET /api/v1/backtest` | **Live** | `BacktestV1Controller` |
| ✅ | `GET /api/v1/backtest/:id` | **Live** | `BacktestV1Controller` |
| ✅ | `GET /api/v1/backtest/:id/equity-points` | **Live** | `BacktestV1Controller` |
| ✅ | `GET /api/v1/backtest/:id/trades` | **Live** | `BacktestV1Controller` |
| ✅ | `GET /api/v1/backtest/:id/candles` | **Live** | `BacktestV1Controller` |
| ✅ | `GET /api/v1/market` | **Live** — OHLCV with symbol/interval/from/to/limit | `MarketQueryController` |
| ⚠️ | `POST /api/v1/montecarlo` | At `/api/monte-carlo/run` — frontend must use old path for now | `MonteCarloController` |
| ⚠️ | WS `/topic/pnl/:accountId` | Publisher exists — verify topic name in `LivePnlWebSocketController` | `LivePnlWebSocketController` |
| 🔵 | `GET /api/v1/market/indicators` | Nice to have — chart overlays | — |
| 🔵 | `GET /api/v1/scheduler` | Nice to have | — |
| 🔵 | `POST /api/v1/scheduler/pause` | Nice to have | — |
| 🔵 | `POST /api/v1/scheduler/resume` | Nice to have | — |

---

## 16. Existing Endpoints Not in Frontend Contract

These endpoints exist in the backend but are not required by the current frontend:

| Endpoint | Controller | Purpose |
|---|---|---|
| `GET /api/v1/trades/account/:accountId/active` | `TradeQueryController` | Active trades by account (superseded by unified `/trades`) |
| `GET /api/v1/trades/account/:accountId/active-pnl` | `TradePnlQueryController` | Active trade P&L by account |
| `GET /api/v1/market/latest-price/:symbol` | `MarketQueryController` | Spot price lookup |
| `GET /api/v1/account-strategies/account/:accountId` | `AccountStrategyController` | Strategies by account |
| `POST /api/backtest/run` | `BacktestController` | Run backtest (path mismatch) |
| `GET /v1/portofolio/reload` | `PortofolioController` | Async portfolio reload |
| `POST /v1/scheduler/start` | `SchedulerController` | Start scheduler |
| `POST /v1/scheduler/stop/:schedulerId` | `SchedulerController` | Stop scheduler |
| `POST /api/monte-carlo/run` | `MonteCarloController` | Monte Carlo (path mismatch) |
| `POST /v1/deep-learning/predict` | `DeepLearningController` | ML prediction |
| `POST /api/historical/warmup` | `HistoricalWarmupController` | Historical data warmup |
| `POST /api/historical/backfill-vcb` | `HistoricalWarmupController` | VCB feature backfill |
| `GET /api/v1/server/ip` | `ServerInfoController` | Server IP |
| `POST /api/v1/server/telegram/send` | `ServerInfoController` | Telegram notification |
| `POST /v1/trade/place-market-order-binance` | `TradeController` | Place Binance order |
| `POST /v1/trade/order-detail-binance` | `TradeController` | Binance order detail |
| WS `@MessageMapping /pnl.subscribe` | `LivePnlWebSocketController` | Subscribe to live P&L |
| WS `@MessageMapping /sentiment.subscribe` | `SentimentWebSocketController` | Subscribe to sentiment |
