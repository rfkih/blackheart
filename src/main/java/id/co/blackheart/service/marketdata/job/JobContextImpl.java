package id.co.blackheart.service.marketdata.job;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.blackheart.model.HistoricalBackfillJob;

import java.util.UUID;

/**
 * Default {@link JobContext} backed by {@link HistoricalBackfillJobLifecycle}.
 * Each setter delegates to the lifecycle bean's
 * {@code REQUIRES_NEW} transactional persister so the DB row reflects the
 * latest progress immediately, even when the handler is mid-window inside
 * an outer transaction (e.g. the bulk backfill's per-window
 * {@code TransactionTemplate.execute}).
 *
 * <p>Result JSON is held in memory and persisted by the runner at completion
 * — sparing one extra round-trip per handler since most handlers compute the
 * result at the very end.
 */
final class JobContextImpl implements JobContext {

    private final UUID jobId;
    private final HistoricalBackfillJob snapshot;
    private final HistoricalBackfillJobLifecycle lifecycle;
    private JsonNode result;

    JobContextImpl(UUID jobId, HistoricalBackfillJob snapshot,
                   HistoricalBackfillJobLifecycle lifecycle) {
        this.jobId = jobId;
        this.snapshot = snapshot;
        this.lifecycle = lifecycle;
    }

    @Override
    public void setPhase(String phase) {
        lifecycle.persistPhase(jobId, phase);
    }

    @Override
    public void setProgress(int done, int total) {
        lifecycle.persistProgress(jobId, done, total);
    }

    @Override
    public boolean isCancellationRequested() {
        return lifecycle.readCancelFlag(jobId);
    }

    @Override
    public void setResult(JsonNode result) {
        this.result = result;
    }

    @Override
    public HistoricalBackfillJob getJob() {
        return snapshot;
    }

    JsonNode getResult() {
        return result;
    }
}
