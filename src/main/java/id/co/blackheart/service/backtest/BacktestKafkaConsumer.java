package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.strategy.BacktestParamOverrideContext;
import id.co.blackheart.service.strategy.BacktestParamPresetContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes messages from the {@code backtest-requests} topic and drives
 * {@link BacktestCoordinatorService#execute}. Replaces the old
 * {@code BacktestAsyncRunner} thread-pool approach.
 *
 * <p>Each message is the UUID string of a {@link BacktestRun} row that has
 * been saved in PENDING state by {@link BacktestService}. The consumer marks
 * it RUNNING, runs the coordinator, then marks it COMPLETED or FAILED before
 * acknowledging the offset. Manual ack means an unclean shutdown (OOM, kill)
 * redelivers the message on restart — the terminal-status guard at the top
 * of {@link #consume} prevents double-execution of completed runs.
 */
@Slf4j
@Service
@Profile("research")
@RequiredArgsConstructor
public class BacktestKafkaConsumer {

    private static final TypeReference<Map<String, Map<String, Object>>> OVERRIDES_TYPE =
            new TypeReference<>() {};

    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    private final BacktestRunLifecycle lifecycle;
    private final BacktestCoordinatorService backtestCoordinatorService;
    private final BacktestProgressTracker progressTracker;
    private final BacktestAnalysisService analysisService;
    private final BacktestRunRepository backtestRunRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.backtest.kafka.topic:backtest-requests}",
            groupId = "backtest-consumer-group",
            containerFactory = "backtestKafkaListenerFactory"
    )
    @SuppressWarnings("java:S1181") // intentional: catch Throwable so OOM marks the run FAILED instead of leaving it permanently RUNNING
    public void consume(String message, Acknowledgment ack) {
        UUID backtestRunId;
        try {
            backtestRunId = UUID.fromString(message.trim());
        } catch (IllegalArgumentException e) {
            log.error("Received non-UUID message from backtest-requests topic | message={}", message);
            ack.acknowledge();
            return;
        }

        log.info("Backtest worker started | runId={}", backtestRunId);

        BacktestRun run = backtestRunRepository.findById(backtestRunId).orElse(null);
        if (run == null) {
            log.warn("Backtest run not found for consumed message — row may have been deleted | runId={}", backtestRunId);
            ack.acknowledge();
            return;
        }

        if (STATUS_COMPLETED.equals(run.getStatus()) || STATUS_FAILED.equals(run.getStatus())) {
            log.warn("Skipping already-terminal backtest run | runId={} status={}", backtestRunId, run.getStatus());
            ack.acknowledge();
            return;
        }

        Map<String, Map<String, Object>> overrides = Collections.emptyMap();
        try {
            run = lifecycle.markRunning(backtestRunId);
            overrides = readOverrides(run);
            executeWithContext(backtestRunId, run, overrides);
        } catch (Throwable t) {
            // Catches both Exception and Error (e.g. OutOfMemoryError). Without
            // catching Error, an OOM would skip markFailed and leave the run
            // permanently RUNNING while the offset still commits in finally.
            log.error("Backtest failed | runId={} overrides={}", backtestRunId, overrides.keySet(), t);
            lifecycle.markFailed(backtestRunId, t);
            progressTracker.fail(backtestRunId);
        } finally {
            ack.acknowledge();
        }
    }

    private void executeWithContext(UUID backtestRunId, BacktestRun run,
                                    Map<String, Map<String, Object>> overrides) {
        try {
            BacktestParamOverrideContext.enter(overrides);
            BacktestParamPresetContext.enter(run.getStrategyParamIds());
            var summary = backtestCoordinatorService.execute(run);
            lifecycle.markCompleted(backtestRunId, summary);
            progressTracker.complete(backtestRunId);
            runPostRunAnalysis(backtestRunId);
        } finally {
            BacktestParamOverrideContext.exit();
            BacktestParamPresetContext.exit();
        }
    }

    private void runPostRunAnalysis(UUID backtestRunId) {
        try {
            analysisService.analyze(backtestRunId);
        } catch (Exception e) {
            log.error("Post-run analysis failed | runId={}", backtestRunId, e);
        }
    }

    private Map<String, Map<String, Object>> readOverrides(BacktestRun run) {
        String json = run.getConfigSnapshot();
        if (!StringUtils.hasText(json)) return Collections.emptyMap();
        try {
            Map<String, Map<String, Object>> parsed = objectMapper.readValue(json, OVERRIDES_TYPE);
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception e) {
            log.warn("Backtest configSnapshot unreadable; ignoring overrides | runId={} err={}",
                    run.getBacktestRunId(), e.getMessage());
            return Collections.emptyMap();
        }
    }
}
