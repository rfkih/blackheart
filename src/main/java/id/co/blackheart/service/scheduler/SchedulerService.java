package id.co.blackheart.service.scheduler;

import id.co.blackheart.dto.response.SchedulerJobStatusResponse;
import id.co.blackheart.model.SchedulerJob;
import id.co.blackheart.repository.SchedulerJobRepository;
import id.co.blackheart.repository.ServerIpLogRepository;
import id.co.blackheart.service.DeepLearningService;
import id.co.blackheart.service.notification.IpMonitorService;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.service.tradequery.StrategyDailyRealizedCurveService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
@Slf4j
@Profile("!research")
@AllArgsConstructor
public class SchedulerService {

    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> schedulerMap = new ConcurrentHashMap<>();
    private final PortfolioService portfolioService;
    private final DeepLearningService deepLearningService;
    private final StrategyDailyRealizedCurveService strategyDailyRealizedCurveService;
    private final SchedulerJobRepository schedulerJobRepository;
    private final ServerIpLogRepository serverIpLogRepository;
    private final IpMonitorService ipMonitorService;

    @PostConstruct
    public void initSchedulersFromDB() {
        List<SchedulerJob> jobs = schedulerJobRepository.findByStatus("1");
        for (SchedulerJob job : jobs) {
            startScheduler(job);
        }
    }

    public synchronized void startScheduler(SchedulerJob job) {
        stopScheduler(job.getJobName());

        CronTrigger cronTrigger = new CronTrigger(job.getCronExpression());
        ScheduledFuture<?> task = taskScheduler.schedule(() -> routeJob(job), cronTrigger);

        schedulerMap.put(job.getJobName(), task);
        log.info("[{}] Scheduled with cron expression: {}", job.getJobName(), job.getCronExpression());
    }

    public synchronized void stopScheduler(String jobName) {
        ScheduledFuture<?> task = schedulerMap.get(jobName);
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
            schedulerMap.remove(jobName);
            log.info("[{}] Scheduler stopped.", jobName);
        }
    }

    /**
     * Apply a partial update to a scheduler job. Either field may be null
     * (leave unchanged). Validates the cron expression by constructing a
     * {@link CronTrigger} before persisting — a malformed cron throws
     * IllegalArgumentException and the in-process schedule is untouched.
     *
     * Reconciliation rules after persist:
     *   status="1" → (re)start the job with the (possibly new) cron
     *   status="0" → stop the job in-process
     *   status null + cronExpression non-null AND job currently scheduled
     *                → restart so the new cron takes effect immediately
     */
    public synchronized SchedulerJobStatusResponse updateJob(Long id,
                                                             String cronExpression,
                                                             String status) {
        SchedulerJob job = schedulerJobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Scheduler job not found: " + id));

        if (cronExpression == null && status == null) {
            throw new IllegalArgumentException("At least one of cronExpression or status must be provided");
        }

        if (cronExpression != null) {
            // Validate before persisting so a bad cron doesn't sneak into the
            // DB. CronTrigger throws IllegalArgumentException on malformed input.
            new CronTrigger(cronExpression);
            job.setCronExpression(cronExpression);
        }
        if (status != null) {
            if (!"0".equals(status) && !"1".equals(status)) {
                throw new IllegalArgumentException("status must be \"0\" or \"1\"");
            }
            job.setStatus(status);
        }

        schedulerJobRepository.save(job);

        if ("1".equals(job.getStatus())) {
            startScheduler(job);
        } else if ("0".equals(job.getStatus())) {
            stopScheduler(job.getJobName());
        }

        ScheduledFuture<?> task = schedulerMap.get(job.getJobName());
        boolean scheduled = task != null && !task.isCancelled() && !task.isDone();
        Instant nextRunAt = computeNextRun(job.getCronExpression());
        return SchedulerJobStatusResponse.builder()
                .id(job.getId())
                .jobName(job.getJobName())
                .jobType(job.getJobType())
                .cronExpression(job.getCronExpression())
                .status(job.getStatus())
                .scheduled(scheduled)
                .nextRunAt(nextRunAt)
                .lastRunAt(null)
                .build();
    }

    /**
     * Manually invoke a scheduler job once, off the HTTP thread. The job's
     * persisted cron and status are unchanged — this is purely a one-shot
     * trigger for operator-initiated runs (e.g. forcing IP_MONITOR after a
     * suspected IP change). Routed via the same {@link #routeJob} switch so
     * exception handling and logging stay identical to the cron path.
     */
    public void runJobOnce(Long id) {
        SchedulerJob job = schedulerJobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Scheduler job not found: " + id));
        // Schedule for "now" so it executes on the TaskScheduler pool, not the
        // request thread. TRAIN_MODEL in particular can take 30+ seconds.
        taskScheduler.schedule(() -> routeJob(job), Instant.now());
        log.info("[{}] Manually triggered (job_id={})", job.getJobName(), id);
    }

    private void routeJob(SchedulerJob job) {
        String jobType = job.getJobType();
        String jobName = job.getJobName();

        // Stamp the heartbeat first, success or fail. The dashboard renders
        // "last fired" — not "last successful run" — so a crashed job should
        // still update the timestamp.
        try {
            schedulerJobRepository.touchLastRun(job.getId(), LocalDateTime.now());
        } catch (Exception heartbeatEx) {
            log.warn("[{}] Failed to stamp last_run_at: {}", jobName, heartbeatEx.getMessage());
        }

        try {
            switch (jobType) {
                case "TRAIN_MODEL":
                    deepLearningService.executeTraining();
                    log.info("[{}][TRAIN_MODEL] Triggered at {}", jobName, LocalDateTime.now());
                    break;

                case "UPDATE_BALANCE":
                    portfolioService.reloadAsset();
                    log.info("[{}][UPDATE_BALANCE] Triggered at {}", jobName, LocalDateTime.now());
                    break;

                case "GENERATE_DAILY_REALIZED_CURVE":
                    strategyDailyRealizedCurveService.generateForYesterday();
                    log.info("[{}][GENERATE_DAILY_REALIZED_CURVE] Triggered at {}", jobName, LocalDateTime.now());
                    break;

                case "IP_MONITOR":
                    ipMonitorService.checkAndNotifyIfChanged();
                    log.info("[{}][IP_MONITOR] Triggered at {}", jobName, LocalDateTime.now());
                    break;

                default:
                    log.warn("[{}] Unknown job type: {}", jobName, jobType);
            }
        } catch (Exception e) {
            log.error("[{}][{}] Scheduler execution failed", jobName, jobType, e);
        }
    }

    /**
     * Returns one row per persisted SchedulerJob, enriched with the in-process
     * schedule state and the next computed fire time. {@code lastRunAt} is
     * read from {@code scheduler_jobs.last_run_at}, which {@link #routeJob}
     * stamps on every fire (V27+). For pre-V27 jobs that haven't fired since
     * the upgrade, the column is null and the dashboard renders "—".
     *
     * <p>IP_MONITOR has a secondary heartbeat in {@code server_ip_log} (the
     * job writes a row on every check). When the row-level column is null
     * but a server_ip_log timestamp is available, fall back to it so
     * existing IP_MONITOR rows don't lose their last-run display across the
     * V27 upgrade.
     */
    public List<SchedulerJobStatusResponse> listJobsWithStatus() {
        List<SchedulerJob> jobs = schedulerJobRepository.findAll();
        Instant ipMonitorLastRun = serverIpLogRepository.findTopByOrderByRecordedAtDesc()
                .map(log -> log.getRecordedAt().atZone(ZoneId.systemDefault()).toInstant())
                .orElse(null);

        List<SchedulerJobStatusResponse> out = new ArrayList<>(jobs.size());
        for (SchedulerJob job : jobs) {
            ScheduledFuture<?> task = schedulerMap.get(job.getJobName());
            boolean scheduled = task != null && !task.isCancelled() && !task.isDone();
            Instant nextRunAt = computeNextRun(job.getCronExpression());
            Instant lastRunAt = job.getLastRunAt() != null
                    ? job.getLastRunAt().atZone(ZoneId.systemDefault()).toInstant()
                    : ("IP_MONITOR".equals(job.getJobType()) ? ipMonitorLastRun : null);
            out.add(SchedulerJobStatusResponse.builder()
                    .id(job.getId())
                    .jobName(job.getJobName())
                    .jobType(job.getJobType())
                    .cronExpression(job.getCronExpression())
                    .status(job.getStatus())
                    .scheduled(scheduled)
                    .nextRunAt(nextRunAt)
                    .lastRunAt(lastRunAt)
                    .build());
        }
        return out;
    }

    /**
     * Compute the next fire time of a cron expression. Returns null on a
     * malformed expression instead of bubbling — the dashboard can render the
     * row with "—" rather than 500'ing the whole panel because one job has a
     * bad cron in the DB.
     */
    private Instant computeNextRun(String cronExpression) {
        try {
            CronTrigger trigger = new CronTrigger(cronExpression);
            return Optional.ofNullable(trigger.nextExecution(new SimpleTriggerContext()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Failed to compute next run for cron '{}': {}", cronExpression, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        schedulerMap.values().forEach(task -> task.cancel(false));
        log.info("Scheduler Manager gracefully shutdown.");
    }
}