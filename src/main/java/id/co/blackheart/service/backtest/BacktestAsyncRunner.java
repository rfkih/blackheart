package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.service.strategy.BacktestParamOverrideContext;
import id.co.blackheart.service.strategy.BacktestParamPresetContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a previously-persisted {@link BacktestRun} on the dedicated backtest
 * executor. Called by {@link BacktestService#runBacktest} which returns the
 * initial PENDING row to the client immediately — the heavy coordinator work
 * happens here off the HTTP request thread.
 *
 * <p>State machine during the run:
 * <pre>
 *   PENDING  — row is enqueued, worker not yet picked up
 *   RUNNING  — worker claimed it, coordinator loop is live (progress ticks)
 *   COMPLETED — summary persisted, progress=100
 *   FAILED   — coordinator threw; progress frozen at last reported value
 * </pre>
 *
 * <p>Each transition is written via {@link BacktestRunLifecycle} in its own
 * REQUIRES_NEW transaction so the status is visible to polling clients as
 * soon as it flips — we don't want a long-running outer transaction to hide
 * the state from readers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestAsyncRunner {

    private static final String STATUS_PENDING = "PENDING";

    /** JSON shape stored in {@code backtest_run.config_snapshot}: string-keyed outer map of
     *  strategy codes, values are override key/value maps. */
    private static final TypeReference<Map<String, Map<String, Object>>> OVERRIDES_TYPE =
            new TypeReference<>() {};

    private final BacktestRunLifecycle lifecycle;
    private final BacktestCoordinatorService backtestCoordinatorService;
    private final BacktestProgressTracker progressTracker;
    private final ObjectMapper objectMapper;
    private final BacktestAnalysisService analysisService;

    @Async("backtestExecutor")
    public void runAsync(UUID backtestRunId) {
        log.info("Backtest worker started | runId={}", backtestRunId);
        Map<String, Map<String, Object>> overrides = Collections.emptyMap();
        try {
            BacktestRun run = lifecycle.markRunning(backtestRunId);
            overrides = readOverrides(run);
            executeWithContext(backtestRunId, run, overrides);
        } catch (Exception e) {
            log.error("Backtest failed | runId={} overrides={}", backtestRunId,
                    overrides.keySet(), e);
            lifecycle.markFailed(backtestRunId, e);
            progressTracker.fail(backtestRunId);
        }
    }

    private void executeWithContext(UUID backtestRunId, BacktestRun run,
                                    Map<String, Map<String, Object>> overrides) {
        try {
            BacktestParamOverrideContext.enter(overrides);
            BacktestParamPresetContext.enter(run.getStrategyParamIds());
            BacktestExecutionSummary summary = backtestCoordinatorService.execute(run);
            lifecycle.markCompleted(backtestRunId, summary);
            progressTracker.complete(backtestRunId);
            runPostRunAnalysis(backtestRunId);
        } finally {
            BacktestParamOverrideContext.exit();
            BacktestParamPresetContext.exit();
        }
    }

    /**
     * Auto-analyze: persist diagnostics to {@code backtest_run.analysis_snapshot}
     * so the frontend + research loop have everything ready without a separate
     * trigger. Swallow errors — a bug in the analyzer shouldn't mark a
     * successfully-completed run as FAILED.
     */
    private void runPostRunAnalysis(UUID backtestRunId) {
        try {
            analysisService.analyze(backtestRunId);
        } catch (Exception analysisEx) {
            log.error("Post-run analysis failed | runId={}", backtestRunId, analysisEx);
        }
    }

    /**
     * Parses the wizard's per-strategy overrides back out of the
     * {@code config_snapshot} column. Returns an empty map when the column is
     * absent, blank, or not JSON the shape we expect — the coordinator will
     * then resolve params exactly as live execution does.
     */
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

    public String statusPending() { return STATUS_PENDING; }
}
