package id.co.blackheart.service.backtest;

import id.co.blackheart.repository.BacktestRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reports backtest progress — to the database (polling fallback) AND over
 * STOMP (realtime push) so clients don't have to hammer the REST endpoint.
 *
 * <p><b>Why {@code TransactionTemplate} instead of {@code @Transactional}:</b>
 * The public entry point {@link #report(UUID, double)} is called from inside
 * the coordinator's tight candle loop. Its implementation invokes the JPA
 * {@code executeUpdate} via {@link #writePercent(UUID, int)} within the same
 * bean. Spring's {@code @Transactional} is AOP-proxy based — a self-invocation
 * inside the same class bypasses the proxy, so the annotation is ignored and
 * {@code executeUpdate} hits a "no transaction in progress" error that the
 * catch block swallows. The result: the bar visibly sticks at 0–1% because
 * none of the progress writes actually commit. Using {@code TransactionTemplate}
 * makes the transaction boundary programmatic and therefore immune to
 * self-invocation.
 *
 * <p><b>Throttling:</b>
 * <ul>
 *   <li>Only writes when the integer percent <em>advances</em> (0 → 1 → 2 …),</li>
 *   <li>At most one DB write per {@link #MIN_INTERVAL_MS} per run id,</li>
 *   <li>STOMP push fires on every advance regardless of DB throttle — the UI
 *       can afford sub-second updates cheaply.</li>
 * </ul>
 */
@Service
@Slf4j
public class BacktestProgressTracker {

    /** Throttle window — a busy run writes to Postgres at most once every 500 ms. */
    private static final long MIN_INTERVAL_MS = 500L;

    /** STOMP topic pattern clients subscribe to. Authorization enforced in
     *  {@code WebSocketAuthInterceptor}. */
    private static final String PROGRESS_TOPIC_PREFIX = "/topic/backtest/";

    private final BacktestRunRepository runRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final TransactionTemplate txTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private final ConcurrentMap<UUID, State> perRun = new ConcurrentHashMap<>();

    public BacktestProgressTracker(
            BacktestRunRepository runRepository,
            SimpMessagingTemplate messagingTemplate,
            PlatformTransactionManager txManager
    ) {
        this.runRepository = runRepository;
        this.messagingTemplate = messagingTemplate;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate.setReadOnly(false);
    }

    private static final class State {
        volatile int lastPercent = -1;
        volatile long lastWriteMs = 0L;
    }

    /**
     * Reports a raw progress fraction (0.0–1.0). Clamped to {@code [0, 99]}
     * so 100 stays reserved for {@link #complete(UUID)} — clients can rely on
     * {@code progressPercent == 100} meaning the coordinator actually returned.
     */
    public void report(UUID runId, double fraction) {
        if (runId == null) return;
        int pct = (int) Math.max(0, Math.min(99, Math.round(fraction * 100)));
        flush(runId, pct, "RUNNING");
    }

    /** Convenience for {@code (step / total)} — avoids float-conversion noise at call sites. */
    public void reportStep(UUID runId, long step, long total) {
        if (total <= 0) return;
        report(runId, (double) step / (double) total);
    }

    /** Marks the run as finished (100%). Also broadcasts the terminal frame. */
    public void complete(UUID runId) {
        if (runId == null) return;
        flush(runId, 100, "COMPLETED");
        perRun.remove(runId);
    }

    /**
     * Cleanup on failure — leaves the last reported percent in the DB (useful
     * to see where the run died) and broadcasts a terminal failure frame so
     * the UI stops polling without waiting on the next REST refetch.
     */
    public void fail(UUID runId) {
        if (runId == null) return;
        State s = perRun.get(runId);
        int lastPercent = s == null ? 0 : Math.max(0, s.lastPercent);
        broadcast(runId, lastPercent, "FAILED");
        perRun.remove(runId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void flush(UUID runId, int pct, String status) {
        State s = perRun.computeIfAbsent(runId, k -> new State());
        long now = Instant.now().toEpochMilli();
        boolean terminal = "COMPLETED".equals(status) || "FAILED".equals(status);

        // STOMP push first — cheap, no DB involvement, fires on every advance
        // (not throttled) so the client sees smooth motion.
        boolean shouldPush;
        synchronized (s) {
            shouldPush = pct > s.lastPercent || terminal;
        }
        if (shouldPush) {
            broadcast(runId, pct, status);
        }

        // DB write — throttled. On terminal flush always writes so the row
        // reconciles with status correctly for late clients / server restarts.
        boolean shouldWriteDb;
        synchronized (s) {
            shouldWriteDb = terminal
                    || (pct > s.lastPercent && now - s.lastWriteMs >= MIN_INTERVAL_MS);
            if (shouldWriteDb) {
                s.lastPercent = pct;
                s.lastWriteMs = now;
            }
        }
        if (shouldWriteDb) {
            writePercent(runId, pct);
        }
    }

    /**
     * Persists the percent via a short-lived {@code REQUIRES_NEW} transaction.
     * Best-effort: a DB hiccup logs a warn but never kills the run.
     */
    private void writePercent(UUID runId, int percent) {
        try {
            txTemplate.executeWithoutResult(status -> entityManager
                    .createQuery("UPDATE BacktestRun r SET r.progressPercent = :p WHERE r.backtestRunId = :id")
                    .setParameter("p", percent)
                    .setParameter("id", runId)
                    .executeUpdate());
        } catch (Exception e) {
            log.warn("Failed to persist backtest progress for runId={}: {}", runId, e.getMessage());
        }
    }

    /**
     * Pushes a STOMP progress frame. Swallows failures — no client listening
     * isn't an error condition, and any messaging-layer issue shouldn't crash
     * the backtest worker.
     */
    private void broadcast(UUID runId, int percent, String status) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("backtestRunId", runId.toString());
            payload.put("status", status);
            payload.put("progressPercent", percent);
            payload.put("timestamp", Instant.now().toEpochMilli());
            messagingTemplate.convertAndSend(PROGRESS_TOPIC_PREFIX + runId, payload);
        } catch (Exception e) {
            log.debug("Progress broadcast failed for runId={}: {}", runId, e.getMessage());
        }
    }

    // Kept only to avoid an unused-import warning on runRepository in future
    // refactors that want to read the current value from the DB.
    @SuppressWarnings("unused")
    private BacktestRunRepository unused() {
        return runRepository;
    }
}
