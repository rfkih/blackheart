package id.co.blackheart.service.marketdata.job.handler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobType;
import id.co.blackheart.service.marketdata.FundingRateBackfillService;
import id.co.blackheart.service.marketdata.FundingRateBackfillService.BackfillResult;
import id.co.blackheart.service.marketdata.job.HistoricalJobHandler;
import id.co.blackheart.service.marketdata.job.JobContext;
import id.co.blackheart.service.marketdata.job.JobParamUtils;
import id.co.blackheart.service.technicalindicator.patcher.FeaturePatcherService;
import id.co.blackheart.service.technicalindicator.patcher.FeaturePatcherService.PatchSummary;
import id.co.blackheart.service.technicalindicator.patcher.FundingColumnsPatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Wraps {@link FundingRateBackfillService#backfillHistorical} as a job-system
 * handler. The underlying service is already idempotent (PK on
 * {@code (symbol, funding_time)}) and self-bounded by {@code HARD_PAGE_CAP},
 * so this handler is mostly a thin adapter — phase + result emission only.
 *
 * <p>Params shape (all optional except symbol via {@code job.symbol}):
 * <pre>
 * {
 *   "from": "2024-01-01T00:00:00"   // ISO LocalDateTime; defaults to 2024-01-01 UTC
 * }
 * </pre>
 *
 * <p>This handler ships in Step 2 to demonstrate the framework end-to-end —
 * it's the simplest existing service to wrap. The richer handlers
 * (PATCH_NULL_COLUMN, RECOMPUTE_RANGE, COVERAGE_REPAIR) ship in Steps 3-4
 * once the FeaturePatcher framework is in place.
 *
 * <p><b>Cancellation</b>: this handler does NOT poll cancel mid-run. The
 * underlying {@link FundingRateBackfillService#backfillHistorical} is a
 * single synchronous call that runs to completion. When the operator clicks
 * Cancel, the runner's post-execute re-read of {@code cancel_requested}
 * sees the flag and marks the job CANCELLED — but the work has already been
 * done (idempotently). The status is technically misleading; the UI
 * communicates this via the cancel-button tooltip.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillFundingHistoryHandler implements HistoricalJobHandler {

    private static final LocalDateTime DEFAULT_FROM = LocalDateTime.of(2024, 1, 1, 0, 0);

    private final FundingRateBackfillService fundingRateBackfillService;
    private final FeaturePatcherService featurePatcherService;
    private final FundingColumnsPatcher fundingColumnsPatcher;

    @Override
    public JobType jobType() {
        return JobType.BACKFILL_FUNDING_HISTORY;
    }

    @Override
    public void execute(HistoricalBackfillJob job, JobContext ctx) {
        String symbol = job.getSymbol();
        if (!StringUtils.hasText(symbol)) {
            throw new IllegalArgumentException(
                    "BACKFILL_FUNDING_HISTORY requires a symbol");
        }

        LocalDateTime from = JobParamUtils.parseLocalDateTime(job.getParams(), "from");
        if (from == null) from = DEFAULT_FROM;

        ctx.setPhase("funding_rate_history");
        log.info("Funding history backfill starting | jobId={} symbol={} from={}",
                job.getJobId(), symbol, from);

        BackfillResult result = fundingRateBackfillService.backfillHistorical(symbol, from);

        ObjectNode resultJson = JsonNodeFactory.instance.objectNode();
        resultJson.put("symbol", result.symbol());
        resultJson.put("from", from.toString());
        resultJson.put("pages", result.pages());
        resultJson.put("fetched", result.fetched());
        resultJson.put("inserted", result.inserted());
        resultJson.put("truncated", result.truncated());

        // Immediately patch feature_store funding columns so they are never
        // left NULL after a successful history fetch. patchAllPairs discovers
        // only (symbol, interval) pairs that still have NULLs, so this is a
        // no-op when the feature_store is already clean. The summary
        // distinguishes rows actually filled from rows still NULL because
        // the upstream funding history is missing for that pair — a 0 in
        // feature_rows_patched no longer hides a silent skip.
        ctx.setPhase("patch_funding_columns");
        Map<String, PatchSummary> patchResult = featurePatcherService.patchAllPairs(fundingColumnsPatcher, ctx);
        int totalPatched = 0;
        int totalSkippedNoSource = 0;
        int totalSkippedNoChange = 0;
        for (PatchSummary s : patchResult.values()) {
            totalPatched += s.patched();
            totalSkippedNoSource += s.skippedNoSource();
            totalSkippedNoChange += s.skippedNoChange();
        }
        resultJson.put("feature_rows_patched", totalPatched);
        resultJson.put("feature_rows_skipped_no_source", totalSkippedNoSource);
        resultJson.put("feature_rows_skipped_no_change", totalSkippedNoChange);

        ctx.setResult(resultJson);
        ctx.setProgress(1, 1);
    }
}
