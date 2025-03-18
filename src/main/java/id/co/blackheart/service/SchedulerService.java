package id.co.blackheart.service;

import id.co.blackheart.dto.SchedulerRequest;
import id.co.blackheart.model.SchedulerJob;
import id.co.blackheart.repository.SchedulerJobRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
@AllArgsConstructor
public class SchedulerService {

    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> schedulerMap = new ConcurrentHashMap<>();
    private final PortfolioService portfolioService;
    private final DeepLearningService deepLearningService;
    private final SchedulerJobRepository schedulerJobRepository;

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
        ScheduledFuture<?> task = taskScheduler.schedule(() -> routeJob(job.getJobType()), cronTrigger);
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

    private void routeJob(String jobType) {
        switch (jobType) {
            case "TRAIN_MODEL":
                deepLearningService.executeTraining();
                log.info("[TRAIN_MODEL] Triggered at {}", LocalDateTime.now());
                break;
            case "UPDATE_BALANCE":
                portfolioService.reloadAsset();
                log.info("[UPDATE_BALANCE] Triggered at {}", LocalDateTime.now());
                break;
            default:
                log.info("Unknown job type: {}", jobType);
        }
    }

    @PreDestroy
    public void shutdown() {
        schedulerMap.values().forEach(task -> task.cancel(false));
        log.info("Scheduler Manager gracefully shutdown.");
    }
}
