package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists {@link BacktestRun} status transitions in their own
 * {@code REQUIRES_NEW} transactions so polling clients see RUNNING /
 * COMPLETED / FAILED flips immediately, independent of the worker's
 * outer execution context.
 *
 * <p><b>Why this lives in its own bean</b>: {@link BacktestAsyncRunner}
 * calls these from a worker thread that may already be in a transaction.
 * Calling {@code @Transactional} methods on the same bean is a no-op —
 * Spring's proxy is bypassed for self-invocation, the {@code REQUIRES_NEW}
 * advice never fires, and status writes silently participate in the
 * caller's transaction (invisible to pollers until that outer tx commits).
 * Putting them on a separate bean forces every call across a proxy
 * boundary so the {@code REQUIRES_NEW} advice always fires.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestRunLifecycle {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_NOTES_LENGTH = 950;

    private final BacktestRunRepository runRepository;

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
        if (run == null) {
            log.warn("markCompleted: backtest run not found | runId={}", runId);
            return;
        }
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
        if (run == null) {
            log.warn("markFailed: backtest run not found | runId={}", runId);
            return;
        }
        run.setStatus(STATUS_FAILED);
        // Keep the last reported percent — useful to see where it died.
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        if (message.length() > MAX_NOTES_LENGTH) {
            message = message.substring(0, MAX_NOTES_LENGTH) + "…";
        }
        run.setNotes(message);
        runRepository.save(run);
    }
}
