package id.co.blackheart.service.marketdata.job;

import id.co.blackheart.model.HistoricalBackfillJob;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Async executor for historical backfill jobs. Mirrors the proven
 * {@code BacktestAsyncRunner} shape but delegates every persistence call to
 * {@link HistoricalBackfillJobLifecycle} so each lifecycle transition runs
 * in its own {@code REQUIRES_NEW} transaction — calls cross a proxy
 * boundary, so {@code @Transactional} advice actually fires.
 *
 * <p>Uses the shared {@code taskExecutor} pool — the same pool that already
 * carries warmup fan-outs for 4h companion intervals. Backfill jobs are
 * I/O-bound (Binance REST + Postgres writes) and don't starve compute.
 *
 * <p>State machine:
 * <pre>
 *   PENDING  → claimed by runner
 *   RUNNING  → handler executing (may emit progress + phase ticks)
 *   SUCCESS  → handler returned without throwing and cancel was not requested
 *   FAILED   → handler threw; error_class + error_message captured
 *   CANCELLED → handler returned and cancel_requested is set
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalBackfillJobAsyncRunner {

    private static final int ERROR_MESSAGE_MAX = 4000;

    private final HistoricalJobHandlerRegistry registry;
    private final HistoricalBackfillJobLifecycle lifecycle;

    /**
     * Enters on the {@code taskExecutor} thread. Three phases:
     * <ol>
     *   <li>{@link #claimJob(UUID)} — flip PENDING → RUNNING, return the
     *       snapshot or {@code null} if the job vanished / pre-cancelled.</li>
     *   <li>{@link #dispatchHandler} — execute the handler with a
     *       {@link JobContextImpl}, catching exceptions.</li>
     *   <li>{@link #finalizeJob} — persist SUCCESS / FAILED / CANCELLED based
     *       on outcome.</li>
     * </ol>
     * All exceptions are caught at one of these phases so the worker thread
     * never dies with an uncaught throwable.
     */
    @Async("taskExecutor")
    public void runAsync(UUID jobId) {
        log.info("Job worker started | jobId={}", jobId);

        HistoricalBackfillJob snapshot = claimJob(jobId);
        if (snapshot == null) return;

        JobContextImpl ctx = new JobContextImpl(jobId, snapshot, lifecycle);
        Throwable thrown = dispatchHandler(snapshot, ctx);
        finalizeJob(snapshot, ctx, thrown);
    }

    /**
     * Phase 1: claim the job by flipping PENDING → RUNNING. Returns null
     * (and short-circuits the worker) when the row is missing, the markRunning
     * transaction fails, or cancellation was already requested before pick-up.
     */
    private HistoricalBackfillJob claimJob(UUID jobId) {
        HistoricalBackfillJob snapshot;
        try {
            snapshot = lifecycle.markRunning(jobId);
        } catch (EntityNotFoundException e) {
            log.warn("Job not found at start | jobId={}", jobId);
            return null;
        } catch (Exception e) {
            log.error("Failed to mark job RUNNING | jobId={}", jobId, e);
            return null;
        }

        if (snapshot.isCancelRequested()) {
            lifecycle.markCancelled(jobId, null);
            log.info("Job cancelled before start | jobId={}", jobId);
            return null;
        }
        return snapshot;
    }

    /**
     * Phase 2: dispatch to the registered handler. Returns null on success,
     * or the captured throwable if the handler (or handler-lookup) failed.
     */
    private Throwable dispatchHandler(HistoricalBackfillJob snapshot, JobContextImpl ctx) {
        var handlerOpt = registry.find(snapshot.getJobType());
        if (handlerOpt.isEmpty()) {
            // Defensive — submission should have rejected this, but if a
            // handler bean was removed between submit and pick-up, fail
            // loud rather than silently.
            return new IllegalStateException(
                    "No handler registered for jobType=" + snapshot.getJobType());
        }
        try {
            handlerOpt.get().execute(snapshot, ctx);
            return null;
        } catch (Exception e) {
            log.error("Job failed | jobId={} type={}", snapshot.getJobId(), snapshot.getJobType(), e);
            return e;
        }
    }

    /**
     * Phase 3: persist the terminal status. Disambiguates SUCCESS vs
     * CANCELLED via a fresh read of {@code cancel_requested} (a handler may
     * have observed cancel mid-run and exited cooperatively without
     * throwing).
     */
    private void finalizeJob(HistoricalBackfillJob snapshot, JobContextImpl ctx, Throwable thrown) {
        UUID jobId = snapshot.getJobId();
        if (thrown != null) {
            String message = thrown.getMessage() == null
                    ? thrown.getClass().getSimpleName()
                    : thrown.getMessage();
            if (message.length() > ERROR_MESSAGE_MAX) {
                message = message.substring(0, ERROR_MESSAGE_MAX) + "…";
            }
            lifecycle.markFailed(jobId, thrown.getClass().getSimpleName(), message);
            return;
        }

        boolean cancelled = lifecycle.readCancelFlag(jobId);
        if (cancelled) {
            lifecycle.markCancelled(jobId, ctx.getResult());
            log.info("Job cancelled | jobId={} type={}", jobId, snapshot.getJobType());
        } else {
            lifecycle.markSuccess(jobId, ctx.getResult());
            log.info("Job complete | jobId={} type={}", jobId, snapshot.getJobType());
        }
    }
}