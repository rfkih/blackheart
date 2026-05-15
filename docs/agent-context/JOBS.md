# Job catalog

> Every `JobType` enum value, the handler that runs it, and a one-liner
> on what it does. Loaded on demand — agents look up `BACKFILL_*` /
> `PATCH_*` here instead of grepping `JobType\.` and chasing `jobType()`
> overrides.

## Conventions

- **JobType enum** lives at `src/main/java/id/co/blackheart/model/JobType.java`.
- **Handlers** implement `HistoricalJobHandler` and live in `src/main/java/id/co/blackheart/service/marketdata/job/handler/`.
- **Dispatch:** `HistoricalJobHandlerRegistry` auto-registers any `@Component` whose `jobType()` returns a unique enum value. Duplicates fail at startup.
- **Async runner:** `HistoricalBackfillJobAsyncRunner.runAsync(UUID)` — claim → dispatch → finalize. Each lifecycle write is `REQUIRES_NEW` via `HistoricalBackfillJobLifecycle` so it crosses a proxy boundary (handler-internal `@Transactional` fires only when called via Spring beans, NOT via `this.method()`).

## JobType → Handler

| JobType | Handler | Path | One-liner |
|---|---|---|---|
| `BACKFILL_FUNDING_HISTORY` | `BackfillFundingHistoryHandler` | `service/marketdata/job/handler/BackfillFundingHistoryHandler.java` | Pulls Binance fapi funding-rate history into `funding_rate_history` (idempotent on `(symbol, funding_time)`), then chains `FundingColumnsPatcher.patchAllPairs` so feature_store funding columns are filled in the same job. |
| `PATCH_NULL_COLUMN` | `PatchNullColumnHandler` | `service/marketdata/job/handler/PatchNullColumnHandler.java` | Fills NULL rows for one feature_store column via the registered `FeaturePatcher`. Auto-discovers `(symbol, interval)` pairs when not specified; `column` param required and must match a `FeaturePatcher.primaryColumn()`. |
| `RECOMPUTE_RANGE` | `RecomputeRangeHandler` | `service/marketdata/job/handler/RecomputeRangeHandler.java` | Destructive delete-then-insert on `feature_store` rows in a `(symbol, interval, from, to)` range — used when indicator code or parameters change and existing rows become stale. |
| `COVERAGE_REPAIR` | `CoverageRepairHandler` | `service/marketdata/job/handler/CoverageRepairHandler.java` | Composite repair: backfills missing `market_data` candles, missing `feature_store` rows, and optionally patches selected NULL columns. Two modes: `warmup` (last 5000 candles + 300-bar warmup) or explicit range. |
| `BACKFILL_ML_FRED` | `BackfillMlFredHandler` (V67, Phase 1 M2) | `service/mlingest/job/handler/BackfillMlFredHandler.java` | Pulls macro/sentiment series from FRED/ALFRED into `macro_raw`. Delegates to Python ingest service via HTTP POST. Params: `{start, end, series_ids[], use_alfred_vintage}`. ALFRED used for revision-prone series (CPI, M2, employment). |
| `BACKFILL_ML_BINANCE_MACRO` | `BackfillMlBinanceMacroHandler` | `service/mlingest/job/handler/BackfillMlBinanceMacroHandler.java` | Pulls Binance public funding rate + OI + L/S ratio + taker buy/sell into `macro_raw`. Delegates to Python. Distinct from `BACKFILL_FUNDING_HISTORY` (which writes `funding_rate_history` for feature_store funding columns). |
| `BACKFILL_ML_DEFILLAMA` | `BackfillMlDefiLlamaHandler` | `service/mlingest/job/handler/BackfillMlDefiLlamaHandler.java` | Pulls stablecoin supply (USDT+USDC) and chain TVL from DefiLlama into `onchain_raw`. |
| `BACKFILL_ML_COINMETRICS` | `BackfillMlCoinMetricsHandler` | `service/mlingest/job/handler/BackfillMlCoinMetricsHandler.java` | Pulls exchange netflow + active addresses from CoinMetrics community tier into `onchain_raw`. |
| `BACKFILL_ML_COINGECKO` | `BackfillMlCoinGeckoHandler` | `service/mlingest/job/handler/BackfillMlCoinGeckoHandler.java` | Pulls BTC dominance + global market caps from CoinGecko free tier into `macro_raw`. |
| `BACKFILL_ML_ALTERNATIVE_ME` | `BackfillMlAlternativeMeHandler` | `service/mlingest/job/handler/BackfillMlAlternativeMeHandler.java` | Pulls Fear & Greed Index history from alternative.me into `macro_raw`. Simplest source — no auth, single endpoint. Implement first as contract validator. |
| `BACKFILL_ML_FOREXFACTORY` | `BackfillMlForexFactoryHandler` | `service/mlingest/job/handler/BackfillMlForexFactoryHandler.java` | Scrapes economic calendar (FOMC, CPI, NFP) from ForexFactory into `macro_raw`. Most fragile source — HTML structure changes periodically. Wrap in robust retry + parse error tolerance. |

## Job result JSON shape (common fields)

All handlers write a `result` JSON to the job row via `ctx.setResult(node)`. Common fields:

- `symbol`, `interval`, `from`, `to` — input echo
- Counts (varies by job): `pages`, `fetched`, `inserted`, `truncated`, `feature_rows_patched`, `feature_rows_skipped_no_source`, `feature_rows_skipped_no_change`, `totalRowsUpdated`
- Per-pair breakdown for auto-discover: `byPair: { "BTCUSDT/5m": {patched, skippedNoSource, skippedNoChange}, ... }`

## Adding a new JobType

1. Add the enum value in `JobType.java`.
2. Drop `@Component class <Name>Handler implements HistoricalJobHandler` in `service/marketdata/job/handler/`. The registry auto-picks it up at startup.
3. Add a row to the table above. Mention `phase` strings the handler emits via `ctx.setPhase(...)` if there's more than one — UI uses them.
4. If the handler patches feature_store columns, prefer chaining `FeaturePatcherService.patchAllPairs` rather than introducing a new write path (see how `BackfillFundingHistoryHandler` does it).

## Cancellation contract

- Lifecycle flips `cancel_requested = true` on the job row.
- Handler is expected to poll `ctx.isCancellationRequested()` between work units.
- A handler that doesn't poll (single synchronous call, e.g. `BackfillFundingHistoryHandler` before the chained patch) still gets marked `CANCELLED` in `finalizeJob` — but the work has already happened (idempotently). UI surfaces this via tooltip.

## Maintenance

Updated **in the same PR** as the code change. See WORKING_RULES.md → "Catalog maintenance" for the trigger list.
