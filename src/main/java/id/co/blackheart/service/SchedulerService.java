package id.co.blackheart.service;

import id.co.blackheart.dto.SchedulerRequest;
import id.co.blackheart.util.TradeConstant;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;


@Service
@Slf4j
@AllArgsConstructor
public class SchedulerService {


    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(5);
    private final Map<String, ScheduledFuture<?>> schedulerMap = new ConcurrentHashMap<>();
    private final PortfolioService portfolioService;

    /**
     * Starts a scheduler for a specific job type
     */
    public synchronized void startScheduler(SchedulerRequest request) {
        stopScheduler(request.getSchedulerId());

        if ("fixed-interval".equalsIgnoreCase(request.getMode())) {
            scheduleFixedInterval(request);
        } else if ("daily-at".equalsIgnoreCase(request.getMode())) {
            scheduleDailyAt(request);
        } else {
            throw new IllegalArgumentException("Unsupported scheduling mode: " + request.getMode());
        }
    }

    private void scheduleFixedInterval(SchedulerRequest req) {
        ScheduledFuture<?> task = executorService.scheduleAtFixedRate(() -> routeJob(req.getJobType()),
                0, req.getIntervalMs(), TimeUnit.MILLISECONDS);

        schedulerMap.put(req.getSchedulerId(), task);
        log.info("[" + req.getSchedulerId() + "] Scheduled every " + req.getIntervalMs() + " ms");
    }

    private void scheduleDailyAt(SchedulerRequest req) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.withHour(req.getHour()).withMinute(req.getMinute()).withSecond(0).withNano(0);

        if (now.isAfter(nextRun)) {
            nextRun = nextRun.plusDays(1);
        }

        long initialDelay = Duration.between(now, nextRun).toMillis();
        long period = Duration.ofDays(1).toMillis();

        ScheduledFuture<?> task = executorService.scheduleAtFixedRate(() -> routeJob(req.getJobType()),
                initialDelay, period, TimeUnit.MILLISECONDS);

        schedulerMap.put(req.getSchedulerId(), task);
        log.info("[" + req.getSchedulerId() + "] Scheduled daily at " + req.getHour() + ":" + String.format("%02d", req.getMinute()));
    }

    public synchronized void stopScheduler(String schedulerId) {
        ScheduledFuture<?> task = schedulerMap.get(schedulerId);
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
            schedulerMap.remove(schedulerId);
            log.info("[" + schedulerId + "] Scheduler stopped.");
        }
    }

    private void routeJob(String jobType) {
        // Example router
        switch (jobType) {
            case "TRAIN_MODEL":

                log.info("[TRAIN_MODEL] Triggered at " + LocalDateTime.now());
                break;
            case "UPDATE_BALANCE":
                portfolioService.reloadAsset();
                log.info("[UPDATE_BALANCE] Triggered at " + LocalDateTime.now());
                break;
            default:
                log.info("Unknown job type: " + jobType);
        }
    }

    @PreDestroy
    public void shutdown() {
        schedulerMap.values().forEach(task -> task.cancel(false));
        executorService.shutdown();
        log.info("Scheduler Manager gracefully shutdown.");
    }

}
