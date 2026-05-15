package id.co.blackheart.service.marketdata.job;

import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobType;

/**
 * Strategy interface for executing one {@link JobType}. Each handler is a
 * Spring bean — the {@link HistoricalJobHandlerRegistry} wires them up at
 * startup keyed by {@link #jobType()}.
 *
 * <p>Handlers should:
 * <ul>
 *   <li>Validate {@code job.getParams()} early and throw
 *       {@link IllegalArgumentException} on malformed input.</li>
 *   <li>Call {@code ctx.setPhase} when crossing a phase boundary.</li>
 *   <li>Call {@code ctx.setProgress} at chunk boundaries (every 500-1000 rows).</li>
 *   <li>Poll {@code ctx.isCancellationRequested()} between chunks and exit
 *       early when set — the runner translates an early return into a
 *       {@code CANCELLED} status when the flag was raised.</li>
 *   <li>Call {@code ctx.setResult} with the final summary JSON before returning.</li>
 * </ul>
 *
 * <p>Throwing from {@code execute} marks the job FAILED with the exception's
 * class + message; the handler itself doesn't need to catch and mark.
 */
public interface HistoricalJobHandler {

    JobType jobType();

    void execute(HistoricalBackfillJob job, JobContext ctx);
}
