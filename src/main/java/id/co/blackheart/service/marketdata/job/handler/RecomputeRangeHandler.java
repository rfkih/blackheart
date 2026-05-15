package id.co.blackheart.service.marketdata.job.handler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobType;
import id.co.blackheart.service.marketdata.job.HistoricalJobHandler;
import id.co.blackheart.service.marketdata.job.JobContext;
import id.co.blackheart.service.marketdata.job.JobParamUtils;
import id.co.blackheart.service.technicalindicator.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Handler for {@link JobType#RECOMPUTE_RANGE} — destructive
 * delete-then-insert on feature_store. Used when indicator code or params
 * have changed and existing rows are stale. The UI gates this behind a
 * confirmation dialog because it's the only repair action that loses
 * existing data.
 *
 * <p>Params shape:
 * <pre>
 * {
 *   "from": "2024-01-15T10:30:00",   // required
 *   "to":   "2025-12-31T23:59:00"    // required
 * }
 * </pre>
 *
 * <p>Delegates to {@link TechnicalIndicatorService#bulkComputeAndStoreInRange}
 * with {@code recompute=true} — the bulk method handles the
 * delete-then-insert per 1-month window, with cancel-rolls-back-current-window
 * semantics and live progress emission via the {@link JobContext}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecomputeRangeHandler implements HistoricalJobHandler {

    private final TechnicalIndicatorService technicalIndicatorService;

    @Override
    public JobType jobType() {
        return JobType.RECOMPUTE_RANGE;
    }

    @Override
    public void execute(HistoricalBackfillJob job, JobContext ctx) {
        if (!StringUtils.hasText(job.getSymbol())
                || !StringUtils.hasText(job.getInterval())) {
            throw new IllegalArgumentException("RECOMPUTE_RANGE requires symbol and interval");
        }

        LocalDateTime from = JobParamUtils.parseLocalDateTime(job.getParams(), "from");
        LocalDateTime to = JobParamUtils.parseLocalDateTime(job.getParams(), "to");
        if (from == null || to == null) {
            throw new IllegalArgumentException(
                    "RECOMPUTE_RANGE requires params.from and params.to");
        }
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException(
                    "params.to must be strictly after params.from: from=" + from + " to=" + to);
        }

        log.info("RECOMPUTE_RANGE | jobId={} symbol={} interval={} from={} to={}",
                job.getJobId(), job.getSymbol(), job.getInterval(), from, to);

        // Bulk path with recompute=true → delete-then-insert in one call,
        // with live progress + cooperative cancel through ctx.
        TechnicalIndicatorService.BulkBackfillResult res =
                technicalIndicatorService.bulkComputeAndStoreInRange(
                        job.getSymbol(), job.getInterval(), from, to, true, ctx);

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("symbol", job.getSymbol());
        result.put("interval", job.getInterval());
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("recompute", true);
        result.put("recordsInserted", res.inserted());
        result.put("recordsSkipped", res.skipped());
        result.put("recordsFailed", res.failed());
        result.put("totalCandidates", res.total());
        ctx.setResult(result);
    }
}
