package id.co.blackheart.service.strategy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-scoped holder that carries per-strategy parameter overrides from the
 * backtest wizard through to the strategy-param services at resolve time.
 *
 * <p><b>Why thread-local and not method-argument plumbing?</b> The chain
 * <ul>
 *   <li>{@code BacktestCoordinatorService} →</li>
 *   <li>{@code TradeListenerService} / {@code StrategyExecutor} fanout →</li>
 *   <li>{@code LsrStrategyService#execute(EnrichedStrategyContext)} →</li>
 *   <li>{@code LsrStrategyParamService#getParams(UUID)}</li>
 * </ul>
 * is shared byte-for-byte between live trading and backtests. Adding an
 * {@code overrides} parameter to every method in that chain would force every
 * live-trade call-site to pass {@code null} — a lot of churn for a feature
 * that only matters in backtests. A bounded thread-local owned by the async
 * runner keeps the modification local to the backtest path and leaves live
 * code unchanged.
 *
 * <p><b>Safety.</b> The backtest executor is a bounded thread pool whose
 * threads are reused across runs. That means a leaked thread-local from a
 * crashed run would poison the next run. {@code BacktestAsyncRunner} always
 * calls {@link #enter(Map)} / {@link #exit()} in a try/finally so a rogue
 * exception inside the coordinator cannot strand a stale override map on the
 * worker thread.
 *
 * <p>Keys in the outer map are strategy codes (case-insensitive). Inner maps
 * follow the same schema as {@code lsr_strategy_param.param_overrides} — the
 * same {@code Map<String, Object>} {@link id.co.blackheart.dto.lsr.LsrParams#merge}
 * and {@link id.co.blackheart.dto.vcb.VcbParams#merge} already consume.
 *
 * <p><b>Package note (V14+):</b> this class lives in {@code service/strategy}
 * not {@code service/backtest} so the trading-service JAR can physically
 * exclude {@code service/backtest/**} without breaking shared strategy code.
 * The class is invoked exclusively by backtest infrastructure at runtime,
 * but its definition is needed on the trading classpath because shared
 * strategy services (LsrStrategyParamService etc.) reference it.
 */
public final class BacktestParamOverrideContext {

    private static final ThreadLocal<Map<String, Map<String, Object>>> HOLDER = new ThreadLocal<>();

    private BacktestParamOverrideContext() {
    }

    /** Replaces the current thread-local map. Caller must pair with {@link #exit()}. */
    public static void enter(Map<String, Map<String, Object>> overridesByStrategy) {
        if (overridesByStrategy == null || overridesByStrategy.isEmpty()) {
            HOLDER.remove();
            return;
        }
        // Defensive copy — shield the thread-local from later mutation by the
        // caller, and normalise the keys to upper-case so {@code LSR} matches
        // {@code lsr} at lookup time.
        Map<String, Map<String, Object>> normalised = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : overridesByStrategy.entrySet()) {
            if (e.getKey() == null) continue;
            Map<String, Object> inner = e.getValue();
            if (inner == null || inner.isEmpty()) continue;
            normalised.put(e.getKey().trim().toUpperCase(), new HashMap<>(inner));
        }
        if (normalised.isEmpty()) {
            HOLDER.remove();
        } else {
            HOLDER.set(normalised);
        }
    }

    /** Clears the current thread-local map. Safe to call when nothing is set. */
    public static void exit() {
        HOLDER.remove();
    }

    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    /**
     * Returns the override map for the given strategy code, or an empty map
     * when nothing is active. Lookup is case-insensitive.
     */
    public static Map<String, Object> forStrategy(String strategyCode) {
        Map<String, Map<String, Object>> all = HOLDER.get();
        if (all == null || strategyCode == null) return Collections.emptyMap();
        Map<String, Object> hit = all.get(strategyCode.trim().toUpperCase());
        return hit == null ? Collections.emptyMap() : hit;
    }
}
