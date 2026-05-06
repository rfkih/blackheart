package id.co.blackheart.service.marketdata.job.handler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobType;
import id.co.blackheart.service.marketdata.HistoricalDataService;
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
 * Handler for {@link JobType#COVERAGE_REPAIR} — the "fill what's missing"
 * backfill that the unified UI submits when the operator checks
 * "Backfill missing market_data candles" or "Backfill missing feature_store
 * rows" on the coverage report.
 *
 * <p>Two modes — both idempotent (skip rows that already exist):
 * <ul>
 *   <li>{@code mode=warmup} — fetch the last 5,000 candles + 300-bar warmup
 *       from Binance and repair missing market_data + feature_store rows.
 *       For interval=4h, fans out to 1h/15m/5m companions automatically
 *       (preserved behavior of the legacy {@code /backfill} endpoint).</li>
 *   <li>{@code mode=range} — fill missing feature_store rows in the
 *       {@code [from, to]} range. Requires market_data already present;
 *       use {@code mode=warmup} first if market_data is sparse.</li>
 * </ul>
 *
 * <p>Mid-run progress is single-shot — the underlying services don't accept
 * a progress callback yet. The handler emits phase boundaries
 * ({@code "market_data"}, {@code "feature_store"}) and a final 1/1 tick on
 * completion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverageRepairHandler implements HistoricalJobHandler {

    private static final String MODE_WARMUP = "warmup";
    private static final String MODE_RANGE = "range";

    private final HistoricalDataService historicalDataService;
    private final TechnicalIndicatorService technicalIndicatorService;

    @Override
    public JobType jobType() {
        return JobType.COVERAGE_REPAIR;
    }

    @Override
    public void execute(HistoricalBackfillJob job, JobContext ctx) {
        if (!StringUtils.hasText(job.getSymbol())
                || !StringUtils.hasText(job.getInterval())) {
            throw new IllegalArgumentException("COVERAGE_REPAIR requires symbol and interval");
        }

        String mode = JobParamUtils.getString(job.getParams(), "mode", MODE_WARMUP);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("symbol", job.getSymbol());
        result.put("interval", job.getInterval());
        result.put("mode", mode);

        switch (mode) {
            case MODE_WARMUP -> runWarmup(job, ctx, result);
            case MODE_RANGE -> runRange(job, ctx, result);
            default -> throw new IllegalArgumentException(
                    "Unknown mode=" + mode + " (must be \"warmup\" or \"range\")");
        }

        ctx.setProgress(1, 1);
        ctx.setResult(result);
    }

    /**
     * Note on cancellation: warmup wraps {@code backfillLastCandlesAndFeatures}
     * which is a single synchronous call (Binance fetch + repair feature_store).
     * Cancel is honored only at the post-execute re-read by the runner — by
     * which point the work is done (idempotently). The UI surfaces this via
     * the cancel-button tooltip so the operator isn't surprised.
     */
    private void runWarmup(HistoricalBackfillJob job, JobContext ctx, ObjectNode result) {
        ctx.setPhase("warmup:market_data+feature_store");
        log.info("COVERAGE_REPAIR warmup | jobId={} symbol={} interval={}",
                job.getJobId(), job.getSymbol(), job.getInterval());
        historicalDataService.backfillLastCandlesAndFeatures(job.getSymbol(), job.getInterval());
        result.put("message", "Warmup completed (last 5000 candles + 300 warmup; companion intervals fanned out for 4h)");
    }

    private void runRange(HistoricalBackfillJob job, JobContext ctx, ObjectNode result) {
        LocalDateTime from = JobParamUtils.parseLocalDateTime(job.getParams(), "from");
        LocalDateTime to = JobParamUtils.parseLocalDateTime(job.getParams(), "to");
        if (from == null || to == null) {
            throw new IllegalArgumentException(
                    "COVERAGE_REPAIR mode=range requires params.from and params.to");
        }
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException(
                    "params.to must be strictly after params.from: from=" + from + " to=" + to);
        }

        log.info("COVERAGE_REPAIR range | jobId={} symbol={} interval={} from={} to={}",
                job.getJobId(), job.getSymbol(), job.getInterval(), from, to);
        // Use the bulk path with a live JobContext so the UI sees per-chunk
        // progress + cooperative cancel. Phase labels are emitted by the
        // service ("bulk:build_indicators", "bulk:write_rows").
        TechnicalIndicatorService.BulkBackfillResult res =
                technicalIndicatorService.bulkComputeAndStoreInRange(
                        job.getSymbol(), job.getInterval(), from, to, false, ctx);
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("recordsInserted", res.inserted());
        result.put("recordsSkipped", res.skipped());
        result.put("recordsFailed", res.failed());
        result.put("totalCandidates", res.total());
    }
}
