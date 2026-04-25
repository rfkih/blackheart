package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.research.BacktestAnalysisService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
 * <p>Each transition is written in its own REQUIRES_NEW transaction so the
 * status is visible to polling clients as soon as it flips — we don't want a
 * long-running outer transaction to hide the state from readers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestAsyncRunner {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    /** JSON shape stored in {@code backtest_run.config_snapshot}: string-keyed outer map of
     *  strategy codes, values are override key/value maps. */
    private static final TypeReference<Map<String, Map<String, Object>>> OVERRIDES_TYPE =
            new TypeReference<>() {};

    private final BacktestRunRepository runRepository;
    private final BacktestCoordinatorService backtestCoordinatorService;
    private final BacktestProgressTracker progressTracker;
    private final ObjectMapper objectMapper;
    private final BacktestAnalysisService analysisService;

    @Async("backtestExecutor")
    public void runAsync(UUID backtestRunId) {
        log.info("Backtest worker started | runId={}", backtestRunId);
        Map<String, Map<String, Object>> overrides = Collections.emptyMap();
        try {
            BacktestRun run = markRunning(backtestRunId);
            overrides = readOverrides(run);
            BacktestParamOverrideContext.enter(overrides);
            try {
                BacktestExecutionSummary summary = backtestCoordinatorService.execute(run);
                markCompleted(backtestRunId, summary);
                progressTracker.complete(backtestRunId);

                // Auto-analyze: persist diagnostics to backtest_run.analysis_snapshot
                // so the frontend + research loop have everything ready without a
                // separate trigger. Swallow errors — a bug in the analyzer shouldn't
                // mark a successfully-completed run as FAILED.
                try {
                    analysisService.analyze(backtestRunId);
                } catch (Exception analysisEx) {
                    log.error("Post-run analysis failed | runId={}", backtestRunId, analysisEx);
                }
            } finally {
                BacktestParamOverrideContext.exit();
            }
        } catch (Exception e) {
            log.error("Backtest failed | runId={} overrides={}", backtestRunId,
                    overrides.keySet(), e);
            markFailed(backtestRunId, e);
            progressTracker.fail(backtestRunId);
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
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            Map<String, Map<String, Object>> parsed = objectMapper.readValue(json, OVERRIDES_TYPE);
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception e) {
            log.warn("Backtest configSnapshot unreadable; ignoring overrides | runId={} err={}",
                    run.getBacktestRunId(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BacktestRun markRunning(UUID runId) {
        BacktestRun run = runRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("Backtest run not found: " + runId));
        run.setStatus(STATUS_RUNNING);
        return runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID runId, BacktestExecutionSummary summary) {
        BacktestRun run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        run.setStatus(STATUS_COMPLETED);
        run.setProgressPercent(100);
        if (summary != null) {
            run.setEndingBalance(summary.getFinalCapital());
            run.setTotalTrades(summary.getTotalTrades());
            run.setTotalWins(summary.getWinningTrades());
            run.setTotalLosses(summary.getLosingTrades());
            run.setWinRate(summary.getWinRate());
            run.setMaxDrawdownPct(summary.getMaxDrawdownPercent());
            run.setGrossProfit(summary.getGrossProfit());
            run.setGrossLoss(summary.getGrossLoss());
            run.setNetProfit(summary.getNetProfit());
        }
        runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID runId, Throwable cause) {
        BacktestRun run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        run.setStatus(STATUS_FAILED);
        // Keep the last reported percent — useful to see where it died.
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();

        if (message.length() > 950) {
            message = message.substring(0, 950) + "…";
        }
        run.setNotes(message);
        runRepository.save(run);
    }

    public String statusPending() { return STATUS_PENDING; }
}
