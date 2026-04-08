package id.co.blackheart.service.scheduler;

import id.co.blackheart.model.SchedulerJob;
import id.co.blackheart.repository.SchedulerJobRepository;
import id.co.blackheart.service.DeepLearningService;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.service.tradequery.StrategyDailyRealizedCurveService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
@Slf4j
@AllArgsConstructor
public class SchedulerService {

    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> schedulerMap = new ConcurrentHashMap<>();
    private final PortfolioService portfolioService;
    private final DeepLearningService deepLearningService;
    private final StrategyDailyRealizedCurveService strategyDailyRealizedCurveService;
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

    private void routeJob(SchedulerJob job) {
        String jobType = job.getJobType();
        String jobName = job.getJobName();

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

                default:
                    log.warn("[{}] Unknown job type: {}", jobName, jobType);
            }
        } catch (Exception e) {
            log.error("[{}][{}] Scheduler execution failed", jobName, jobType, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        schedulerMap.values().forEach(task -> task.cancel(false));
        log.info("Scheduler Manager gracefully shutdown.");
    }
}