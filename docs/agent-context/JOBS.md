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
