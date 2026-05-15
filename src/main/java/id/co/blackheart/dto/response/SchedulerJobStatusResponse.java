package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Snapshot of one scheduler job for the /research dashboard. Combines DB
 * configuration with the live in-process schedule state and the next computed
 * fire time. {@code lastRunAt} is best-effort and only populated for jobs that
 * persist a heartbeat (currently IP_MONITOR via ServerIpLog) — null for the
 * rest, which the dashboard renders as an em-dash.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SchedulerJobStatusResponse {
    private Long id;
    private String jobName;
    private String jobType;
    private String cronExpression;
    /** Persisted "1" = enabled, "0" = disabled (legacy column convention). */
    private String status;
    /** True iff the in-process schedulerMap has an active ScheduledFuture for this job. */
    private boolean scheduled;
    /** Next fire time computed from the cron expression; null if cron is malformed. */
    private Instant nextRunAt;
    /** Last observed run time, if the job persists a heartbeat. Best-effort. */
    private Instant lastRunAt;
}
