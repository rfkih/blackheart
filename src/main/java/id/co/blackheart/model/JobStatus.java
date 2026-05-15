package id.co.blackheart.model;

/**
 * Lifecycle states for {@link HistoricalBackfillJob}. Mirrors the
 * {@code chk_historical_backfill_job_status} CHECK constraint in V47 — keep
 * the two in sync. Persisted as VARCHAR via {@code @Enumerated(STRING)}.
 */
public enum JobStatus {
    /** Inserted by the controller; not yet picked up by the async runner. */
    PENDING,
    /** Async worker has claimed it and the handler is executing. */
    RUNNING,
    /** Handler returned without throwing. {@code finished_at} is set. */
    SUCCESS,
    /** Handler threw. {@code error_message} / {@code error_class} are set. */
    FAILED,
    /** Operator requested cancel and the handler cooperatively exited early. */
    CANCELLED
}
