package id.co.blackheart.service.marketdata.job;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.blackheart.model.HistoricalBackfillJob;

/**
 * Runtime helper passed to {@link HistoricalJobHandler#execute}. Lets the
 * handler emit progress, check for cooperative cancellation, and stash the
 * final result without touching the {@link HistoricalBackfillJob} entity or
 * any repository directly.
 *
 * <p>Each {@code setPhase} / {@code setProgress} call persists to the DB in
 * a fresh transaction so polling clients (and STOMP subscribers, once wired)
 * see the update immediately, even if the handler is mid-way through a long
 * outer transaction. Handlers should batch progress emits — once per chunk
 * (e.g. every 500 rows) is plenty.
 */
public interface JobContext {

    /** Set the coarse phase label (e.g. {@code "market_data"}, {@code "patch:slope_200"}). */
    void setPhase(String phase);

    /** Set the absolute progress counters. */
    void setProgress(int done, int total);

    /** Refresh the cancel flag from the DB and report whether cancel was requested. */
    boolean isCancellationRequested();

    /** Stash the JSON result the controller will return on completion. */
    void setResult(JsonNode result);

    /**
     * Read-only view of the current job snapshot — useful for handlers that
     * need to inspect their own params or symbol/interval. Not refreshed
     * mid-run; call again to re-read.
     */
    HistoricalBackfillJob getJob();
}
