package id.co.blackheart.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-{@code accountStrategyId} rolling-window error counter for spec-driven
 * engine evaluations (parametric blueprint invariant 9).
 *
 * <p>Each call to {@link #recordError} appends a timestamp; entries older
 * than {@code engine.kill-switch.error-window-minutes} fall off. When the
 * window holds {@code engine.kill-switch.error-threshold} or more entries
 * the kill switch is tripped via {@link EngineKillSwitchService}, blocking
 * further entries until an operator rearms.
 *
 * <p>Trip is sticky per process: once we ask the service to trip, this
 * counter ignores further errors for that {@code accountStrategyId} until
 * {@link #reset} is invoked (e.g. on rearm) — otherwise we would spam the
 * service with redundant trip attempts every bar while errors continue.
 *
 * <p>Counters and successes are also tracked for the metrics endpoint shipped
 * in M4.2; this class is the single in-memory source of truth for engine
 * health.
 */
@Component
@Slf4j
public class EngineMetrics {

    private final EngineKillSwitchService killSwitchService;
    private final int errorThreshold;
    private final long errorWindowMillis;
    private final LongSupplier clockMillis;

    private final Map<UUID, ErrorWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> evalCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errorCount = new ConcurrentHashMap<>();

    @Autowired
    public EngineMetrics(EngineKillSwitchService killSwitchService,
                         @Value("${engine.kill-switch.error-threshold:5}") int errorThreshold,
                         @Value("${engine.kill-switch.error-window-minutes:10}") long errorWindowMinutes) {
        this(killSwitchService, errorThreshold, errorWindowMinutes, System::currentTimeMillis);
    }

    /** Test-only constructor with injectable clock. */
    EngineMetrics(EngineKillSwitchService killSwitchService,
                  int errorThreshold,
                  long errorWindowMinutes,
                  LongSupplier clockMillis) {
        this.killSwitchService = killSwitchService;
        this.errorThreshold = Math.max(1, errorThreshold);
        this.errorWindowMillis = Math.max(1L, errorWindowMinutes) * 60_000L;
        this.clockMillis = clockMillis;
        log.info("EngineMetrics initialised | errorThreshold={} errorWindowMinutes={}",
                this.errorThreshold, errorWindowMinutes);
    }

    /** Record a successful evaluation. Used only for observability counters. */
    public void recordSuccess(String strategyCode) {
        if (strategyCode == null) return;
        evalCount.computeIfAbsent(strategyCode, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Record an error from the spec-driven adapter. May trip the kill switch
     * synchronously inside this call (the trip itself uses a REQUIRES_NEW
     * transaction so the caller's tx is unaffected).
     */
    public void recordError(UUID accountStrategyId, String strategyCode, Throwable error) {
        if (strategyCode != null) {
            evalCount.computeIfAbsent(strategyCode, k -> new AtomicLong()).incrementAndGet();
            errorCount.computeIfAbsent(strategyCode, k -> new AtomicLong()).incrementAndGet();
        }
        if (accountStrategyId == null) return;

        long now = clockMillis.getAsLong();
        ErrorWindow w = windows.computeIfAbsent(accountStrategyId, k -> new ErrorWindow());
        int observed = w.recordAndSize(now, errorWindowMillis);

        if (observed >= errorThreshold && w.markTrippedIfFirst()) {
            String reason = String.format(
                    "Spec engine error rate %d errors in %d minutes (threshold %d) — last: %s",
                    observed,
                    errorWindowMillis / 60_000L,
                    errorThreshold,
                    error == null ? "n/a" : error.getClass().getSimpleName() + ": " + error.getMessage());
            killSwitchService.tripFromEngineErrors(accountStrategyId, reason);
        }
    }

    /**
     * Clear in-memory error history for the given strategy. Intended hook
     * for the rearm endpoint (M4.2 wiring) so a freshly rearmed strategy
     * starts on a clean window rather than re-tripping immediately on the
     * next error.
     */
    public void reset(UUID accountStrategyId) {
        if (accountStrategyId == null) return;
        windows.remove(accountStrategyId);
    }

    // ── Observability accessors (M4.2) ───────────────────────────────────────

    public long getEvalCount(String strategyCode) {
        AtomicLong c = evalCount.get(strategyCode);
        return c == null ? 0L : c.get();
    }

    public long getErrorCount(String strategyCode) {
        AtomicLong c = errorCount.get(strategyCode);
        return c == null ? 0L : c.get();
    }

    public Map<String, AtomicLong> evalCountSnapshot() {
        return Map.copyOf(evalCount);
    }

    public Map<String, AtomicLong> errorCountSnapshot() {
        return Map.copyOf(errorCount);
    }

    /**
     * Per-{@code accountStrategyId} error window. Synchronized on the
     * instance — contention is per-strategy so a coarse lock is fine and
     * keeps the data structure obvious.
     */
    private static final class ErrorWindow {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private boolean tripped;

        synchronized int recordAndSize(long now, long windowMillis) {
            long cutoff = now - windowMillis;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            timestamps.addLast(now);
            return timestamps.size();
        }

        synchronized boolean markTrippedIfFirst() {
            if (tripped) return false;
            tripped = true;
            return true;
        }
    }
}
