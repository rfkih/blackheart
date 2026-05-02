package id.co.blackheart.service.alert;

import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.SchedulerJob;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.repository.SchedulerJobRepository;
import id.co.blackheart.repository.TradesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Phase 7.3 — five-minute liveness watchdog.
 *
 * <p>Three classes of failure are checked every tick and, on detection,
 * pushed through {@link AlertService} so the operator gets a Telegram /
 * email even if the trading JVM is otherwise silent.
 *
 * <ol>
 *   <li><b>Scheduler dead-man</b> — every enabled {@code scheduler_jobs}
 *       row should have a {@code last_run_at} no older than its cron's
 *       next-fire-after-last-run plus a small grace window. Catches the
 *       case where a job thread silently hangs.</li>
 *   <li><b>Trade stuck</b> — any {@code Trades} row in OPEN /
 *       PARTIALLY_CLOSED whose {@code entry_time} is older than the
 *       configured threshold. Catches a botched close (parent never
 *       flipped) or a TP/SL listener wedge.</li>
 *   <li><b>Ingest stalled</b> — newest {@code market_data} row for the
 *       live symbol+interval is older than the configured threshold.
 *       Catches a Binance WebSocket reconnect storm or a feature-store
 *       writer fault.</li>
 * </ol>
 *
 * <p>Profile-gated to {@code !research}: only the trading JVM owns the
 * live ingest path and the live position book, so only it can authoritatively
 * answer these questions. The research JVM hosts a stale view.
 *
 * <p>Each check is wrapped so that a single failure (bad cron expression,
 * a transient DB hiccup) doesn't stop the other two from running.
 */
@Component
@Profile("!research")
@ConditionalOnProperty(name = "app.watchdog.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class LivenessWatchdogService {

    private final AlertService alertService;
    private final SchedulerJobRepository schedulerJobRepository;
    private final TradesRepository tradesRepository;
    private final MarketDataRepository marketDataRepository;

    @Value("${app.watchdog.scheduler-grace-minutes:10}")
    private long schedulerGraceMinutes;

    @Value("${app.watchdog.trade-stuck-hours:24}")
    private long tradeStuckHours;

    @Value("${app.watchdog.ingest-stalled-minutes:15}")
    private long ingestStalledMinutes;

    @Value("${app.live.symbol:BTCUSDT}")
    private String liveSymbol;

    /**
     * Comma-separated intervals to monitor for ingest freshness. Defaults
     * to {@code 5m} — that's the WebSocket-streamed live cadence, which is
     * the canonical "is data flowing" signal. Add larger intervals only
     * if their absence is operationally meaningful.
     */
    @Value("${app.watchdog.ingest-intervals:5m}")
    private String ingestIntervalsCsv;

    @Scheduled(
            fixedDelayString = "${app.watchdog.interval-ms:300000}",
            initialDelayString = "${app.watchdog.initial-delay-ms:60000}"
    )
    public void tick() {
        runSafely("scheduler-heartbeat", this::checkSchedulerHeartbeats);
        runSafely("trade-stuck", this::checkStuckTrades);
        runSafely("ingest-stalled", this::checkIngestFreshness);
    }

    private void runSafely(String name, Runnable check) {
        try {
            check.run();
        } catch (RuntimeException e) {
            log.error("[Watchdog] check {} threw — continuing", name, e);
        }
    }

    void checkSchedulerHeartbeats() {
        List<SchedulerJob> enabled = schedulerJobRepository.findByStatus("1");
        for (SchedulerJob job : enabled) {
            // A null lastRunAt means the job is configured but has never
            // fired. The dashboard already surfaces that as a missing
            // heartbeat; alerting here would noise up every fresh deploy.
            if (job.getLastRunAt() == null) continue;

            CronExpression cron;
            try {
                cron = CronExpression.parse(job.getCronExpression());
            } catch (IllegalArgumentException e) {
                log.warn("[Watchdog] Invalid cron on job {}: {}",
                        job.getJobName(), job.getCronExpression());
                continue;
            }

            // Mirror SchedulerService.buildCronTrigger: FUNDING_INGEST is
            // UTC-zoned, every other job uses the JVM-default zone. The
            // routeJob writer always stamps last_run_at via
            // LocalDateTime.now() (JVM-default), so for UTC-zoned jobs we
            // must convert before feeding the cron — otherwise next() walks
            // forward from a wall-clock that's offset from the cron's grid.
            ZoneId cronZone = "FUNDING_INGEST".equals(job.getJobType())
                    ? ZoneOffset.UTC
                    : ZoneId.systemDefault();
            LocalDateTime lastRunInZone = job.getLastRunAt()
                    .atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(cronZone)
                    .toLocalDateTime();
            LocalDateTime nowInZone = LocalDateTime.now(cronZone);

            LocalDateTime expected = cron.next(lastRunInZone);
            if (expected == null) continue;
            LocalDateTime deadline = expected.plusMinutes(schedulerGraceMinutes);
            if (nowInZone.isAfter(deadline)) {
                long minutesLate = ChronoUnit.MINUTES.between(expected, nowInZone);
                alertService.raise(
                        AlertSeverity.CRITICAL,
                        "SCHEDULER_DEAD_MAN",
                        String.format(
                                "Scheduler job '%s' (%s) hasn't fired since %s — expected %s, %d min late",
                                job.getJobName(),
                                job.getJobType(),
                                job.getLastRunAt(),
                                expected,
                                minutesLate),
                        "sched_dead_" + job.getJobName());
            }
        }
    }

    void checkStuckTrades() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(tradeStuckHours);
        List<Trades> open = tradesRepository.findAllOpen();
        for (Trades t : open) {
            if (t.getEntryTime() == null) continue;
            if (t.getEntryTime().isBefore(cutoff)) {
                alertService.raise(
                        AlertSeverity.WARN,
                        "TRADE_STUCK",
                        String.format(
                                "Trade %s on %s/%s (%s, status=%s) open since %s — exceeds %dh threshold",
                                t.getTradeId(),
                                t.getAsset(),
                                t.getInterval(),
                                t.getSide(),
                                t.getStatus(),
                                t.getEntryTime(),
                                tradeStuckHours),
                        "trade_stuck_" + t.getTradeId());
            }
        }
    }

    void checkIngestFreshness() {
        if (liveSymbol == null || liveSymbol.isBlank()) return;
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(ingestStalledMinutes);
        for (String raw : ingestIntervalsCsv.split(",")) {
            String interval = raw.trim();
            if (interval.isEmpty()) continue;

            MarketData latest = marketDataRepository.findLatestBySymbol(liveSymbol, interval);
            if (latest == null) {
                alertService.raise(
                        AlertSeverity.CRITICAL,
                        "INGEST_NO_DATA",
                        String.format("No market_data rows at all for %s/%s", liveSymbol, interval),
                        "ingest_nodata_" + liveSymbol + "_" + interval);
                continue;
            }
            LocalDateTime startTime = latest.getStartTime();
            if (startTime != null && startTime.isBefore(cutoff)) {
                long minutesStale = ChronoUnit.MINUTES.between(startTime, LocalDateTime.now());
                alertService.raise(
                        AlertSeverity.CRITICAL,
                        "INGEST_STALLED",
                        String.format(
                                "Ingest stalled for %s/%s — latest candle start_time=%s (%d min ago), threshold=%dmin",
                                liveSymbol,
                                interval,
                                startTime,
                                minutesStale,
                                ingestStalledMinutes),
                        "ingest_stalled_" + liveSymbol + "_" + interval);
            }
        }
    }
}
