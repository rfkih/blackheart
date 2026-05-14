package id.co.blackheart.service.mlingest.job.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.service.marketdata.job.HistoricalJobHandler;
import id.co.blackheart.service.marketdata.job.JobContext;
import id.co.blackheart.service.marketdata.job.JobParamUtils;
import id.co.blackheart.service.mlingest.MlIngestPythonClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Shared base for the seven {@code BACKFILL_ML_*} handlers.
 *
 * <p><b>Dual-mode dispatch.</b> Each concrete subclass declares its
 * {@link #jobType()} and {@link #sourceName()}; this class decides at
 * runtime whether to:
 * <ul>
 *   <li><b>Delegate to Python</b> — when {@code ingest.python.base-url} is
 *       configured AND the Python service has the source module
 *       implemented. POSTs to {@code /pull/{source}} and stashes the real
 *       result JSON.</li>
 *   <li><b>Run the stub</b> — when Python is not configured, when the
 *       source isn't implemented Python-side yet (501 from {@code /pull}),
 *       or when no client bean is available at all (legacy tests). Stub
 *       simulates ~15 seconds of progress so the UI pipeline is
 *       exercised even before the Python module ships.</li>
 * </ul>
 *
 * <p>Real failures (network, 5xx, source raised) propagate as
 * {@link IllegalStateException} and mark the job FAILED — never silently
 * fall back to stub on a real error, that would mask data-pipeline
 * problems.
 */
@Slf4j
public abstract class MlBackfillStubHandler implements HistoricalJobHandler {

    private static final int STUB_PROGRESS_CHUNKS = 10;
    private static final Duration STUB_CHUNK_DURATION = Duration.ofMillis(1500);

    /**
     * Setter-injected so subclasses don't need explicit constructors.
     * Optional dependency — when missing (dev/tests), handler always runs
     * stub. Spring autowires per concrete subclass bean.
     */
    private MlIngestPythonClient pythonClient;

    @Autowired(required = false)
    public void setPythonClient(MlIngestPythonClient pythonClient) {
        this.pythonClient = pythonClient;
    }

    /**
     * Identifier matching {@code ml_ingest_schedule.source} — used in log
     * lines, the Python dispatch URL, and the result JSON.
     */
    protected abstract String sourceName();

    @Override
    public final void execute(HistoricalBackfillJob job, JobContext ctx) {
        LocalDateTime start = JobParamUtils.parseLocalDateTime(job.getParams(), "start");
        LocalDateTime end = JobParamUtils.parseLocalDateTime(job.getParams(), "end");

        log.info("ML backfill starting | jobId={} source={} symbol={} start={} end={}",
                job.getJobId(), sourceName(), job.getSymbol(), start, end);

        if (start == null || end == null) {
            throw new IllegalArgumentException(
                    "BACKFILL_ML_" + sourceName().toUpperCase()
                            + " requires both 'start' and 'end' in params");
        }

        // Decide stub-vs-python at job-start. Python failures (other than
        // 501 NOT_IMPLEMENTED) propagate as IllegalStateException so the
        // job row is marked FAILED — we don't want stub fallback to hide
        // real data-pipeline issues.
        if (pythonClient != null && pythonClient.isEnabled()) {
            try {
                executePython(job, ctx, start, end);
                return;
            } catch (MlIngestPythonClient.NotImplementedException e) {
                log.info("Python source {} returns 501 — running stub", sourceName());
                // fall through to stub
            } catch (MlIngestPythonClient.DisabledException ignored) {
                // shouldn't happen given isEnabled() guard, but be safe
            }
        }

        executeStub(job, ctx, start, end);
    }

    // ── Python delegation ──────────────────────────────────────────────────

    private void executePython(HistoricalBackfillJob job, JobContext ctx,
                                LocalDateTime start, LocalDateTime end) {
        ctx.setPhase("calling_python_ingest");
        ctx.setProgress(0, 1);

        JsonNode config = extractConfig(job);
        JsonNode response = pythonClient.pull(
                sourceName(),
                start,
                end,
                job.getSymbol(),
                config
        );

        ctx.setPhase("done");
        ctx.setResult(response);
        ctx.setProgress(1, 1);

        long inserted = response.has("rows_inserted") ? response.get("rows_inserted").asLong() : 0;
        log.info("ML backfill (python) complete | jobId={} source={} rows_inserted={}",
                job.getJobId(), sourceName(), inserted);
    }

    /**
     * Extract the {@code config} sub-object from the job's {@code params}.
     * Returns a JsonNode that's passed through verbatim to the Python
     * client — no intermediate Object conversion, so numeric precision is
     * preserved end-to-end.
     *
     * <p>Two input shapes accepted:
     * <ul>
     *   <li>Explicit: {@code {"config": {"series_ids": [...]}}} — wins if present.</li>
     *   <li>Promoted: top-level non-reserved keys (anything other than
     *       {@code start}, {@code end}, {@code symbol}, {@code config}) are
     *       collected into a new config object.</li>
     * </ul>
     */
    private JsonNode extractConfig(HistoricalBackfillJob job) {
        JsonNode params = job.getParams();
        if (params == null || params.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }

        JsonNode explicit = params.get("config");
        if (explicit != null && explicit.isObject()) {
            return explicit;
        }

        // Promote non-reserved top-level keys into a synthetic config node.
        ObjectNode promoted = JsonNodeFactory.instance.objectNode();
        params.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!"start".equals(key) && !"end".equals(key)
                    && !"symbol".equals(key) && !"config".equals(key)) {
                promoted.set(key, entry.getValue());
            }
        });
        return promoted;
    }

    // ── Stub simulation ────────────────────────────────────────────────────

    private void executeStub(HistoricalBackfillJob job, JobContext ctx,
                              LocalDateTime start, LocalDateTime end) {
        ctx.setPhase("connecting_to_python_ingest");
        sleepQuietly(STUB_CHUNK_DURATION);

        if (ctx.isCancellationRequested()) {
            log.info("ML backfill stub cancelled before fetch | jobId={} source={}",
                    job.getJobId(), sourceName());
            return;
        }

        ctx.setPhase("fetching_" + sourceName() + "_data");
        ctx.setProgress(0, STUB_PROGRESS_CHUNKS);

        for (int i = 1; i <= STUB_PROGRESS_CHUNKS; i++) {
            if (ctx.isCancellationRequested()) {
                log.info("ML backfill stub cancelled mid-run | jobId={} source={} progress={}/{}",
                        job.getJobId(), sourceName(), i - 1, STUB_PROGRESS_CHUNKS);
                return;
            }
            sleepQuietly(STUB_CHUNK_DURATION);
            ctx.setProgress(i, STUB_PROGRESS_CHUNKS);
        }

        ctx.setPhase("done");
        ctx.setResult(buildStubResult(job, start, end));
        log.info("ML backfill (stub) complete | jobId={} source={}",
                job.getJobId(), sourceName());
    }

    private ObjectNode buildStubResult(HistoricalBackfillJob job, LocalDateTime start, LocalDateTime end) {
        ObjectNode resultJson = JsonNodeFactory.instance.objectNode();
        resultJson.put("stub", true);
        resultJson.put("source", sourceName());
        resultJson.put("symbol", job.getSymbol());
        resultJson.put("start", start != null ? start.toString() : null);
        resultJson.put("end", end != null ? end.toString() : null);
        resultJson.put("rows_inserted", 0);
        resultJson.put("note", "Python ingest service not yet implemented for this source "
                + "(Phase 1 M2 staged rollout). This is a stub run that exercises the "
                + "full Java + frontend pipeline.");
        return resultJson;
    }

    private void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during ML backfill stub", e);
        }
    }
}
