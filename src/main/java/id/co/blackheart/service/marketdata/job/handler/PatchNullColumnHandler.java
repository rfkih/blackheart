package id.co.blackheart.service.marketdata.job.handler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobType;
import id.co.blackheart.service.marketdata.job.HistoricalJobHandler;
import id.co.blackheart.service.marketdata.job.JobContext;
import id.co.blackheart.service.marketdata.job.JobParamUtils;
import id.co.blackheart.service.technicalindicator.patcher.FeaturePatcher;
import id.co.blackheart.service.technicalindicator.patcher.FeaturePatcherRegistry;
import id.co.blackheart.service.technicalindicator.patcher.FeaturePatcherService;
import id.co.blackheart.service.technicalindicator.patcher.FeaturePatcherService.PatchSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Handler for {@link JobType#PATCH_NULL_COLUMN}. Looks up the requested
 * column in {@link FeaturePatcherRegistry}, then dispatches to
 * {@link FeaturePatcherService} for either single-pair or auto-discovery
 * mode.
 *
 * <p>Params shape:
 * <pre>
 * {
 *   "column": "slope_200",          // required — must be a registered patcher
 *   "from":   "2024-01-15T10:30:00", // optional, single-pair only
 *   "to":     "2025-12-31T23:59:00"  // optional, single-pair only
 * }
 * </pre>
 *
 * <p>Pair scoping:
 * <ul>
 *   <li>{@code symbol} + {@code interval} on the job → patch that single
 *       pair (with {@code from}/{@code to} from params, or full NULL bounds
 *       when omitted).</li>
 *   <li>{@code symbol} + {@code interval} both null → auto-discover every
 *       pair with a NULL row in the column and patch each.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatchNullColumnHandler implements HistoricalJobHandler {

    private final FeaturePatcherRegistry registry;
    private final FeaturePatcherService patcherService;

    @Override
    public JobType jobType() {
        return JobType.PATCH_NULL_COLUMN;
    }

    @Override
    public void execute(HistoricalBackfillJob job, JobContext ctx) {
        String column = JobParamUtils.requireString(job.getParams(), "column");
        FeaturePatcher<?> patcher = registry.find(column).orElseThrow(() ->
                new IllegalArgumentException("No patcher registered for column=" + column
                        + " (registered: " + registry.registeredColumns() + ")"));

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("column", column);
        result.put("writtenColumns", String.join(",", patcher.writtenColumns()));

        boolean autoDiscover = job.getSymbol() == null || job.getInterval() == null;

        if (autoDiscover) {
            ctx.setPhase("patch:" + column + " (auto-discover)");
            log.info("PATCH_NULL_COLUMN auto-discover | jobId={} column={}", job.getJobId(), column);
            Map<String, PatchSummary> byPair = patcherService.patchAllPairs(patcher, ctx);
            ObjectNode byPairNode = result.putObject("byPair");
            int totalPatched = 0;
            int totalSkippedNoSource = 0;
            int totalSkippedNoChange = 0;
            for (var e : byPair.entrySet()) {
                PatchSummary s = e.getValue();
                ObjectNode pairNode = byPairNode.putObject(e.getKey());
                pairNode.put("patched", s.patched());
                pairNode.put("skippedNoSource", s.skippedNoSource());
                pairNode.put("skippedNoChange", s.skippedNoChange());
                totalPatched += s.patched();
                totalSkippedNoSource += s.skippedNoSource();
                totalSkippedNoChange += s.skippedNoChange();
            }
            result.put("totalRowsUpdated", totalPatched);
            result.put("totalSkippedNoSource", totalSkippedNoSource);
            result.put("totalSkippedNoChange", totalSkippedNoChange);
            result.put("pairsProcessed", byPair.size());
        } else {
            ctx.setPhase("patch:" + column + " " + job.getSymbol() + "/" + job.getInterval());
            LocalDateTime from = JobParamUtils.parseLocalDateTime(job.getParams(), "from");
            LocalDateTime to = JobParamUtils.parseLocalDateTime(job.getParams(), "to");
            log.info("PATCH_NULL_COLUMN single-pair | jobId={} column={} symbol={} interval={} from={} to={}",
                    job.getJobId(), column, job.getSymbol(), job.getInterval(), from, to);
            PatchSummary summary = patcherService.patchPair(patcher, job.getSymbol(), job.getInterval(),
                    from, to, ctx);
            result.put("symbol", job.getSymbol());
            result.put("interval", job.getInterval());
            if (from != null) result.put("from", from.toString());
            if (to != null) result.put("to", to.toString());
            result.put("totalRowsUpdated", summary.patched());
            result.put("skippedNoSource", summary.skippedNoSource());
            result.put("skippedNoChange", summary.skippedNoChange());
            ctx.setProgress(summary.patched(), Math.max(summary.total(), 1));
        }

        ctx.setResult(result);
    }
}
