package id.co.blackheart.service.strategy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thread-scoped holder for per-strategy preset pinning during a backtest run.
 * Mirrors {@link BacktestParamOverrideContext} but carries
 * {@code strategy_param.param_id} pointers rather than diff maps.
 *
 * <p>Resolution rule applied by {@link StrategyParamService#resolveOverridesForStrategy}:
 * <ol>
 *   <li>If this context has a paramId for the strategy code, load that preset
 *       (including soft-deleted rows) via {@code getOverridesByParamId}.</li>
 *   <li>Otherwise fall through to {@code getActiveOverrides(accountStrategyId)}.</li>
 * </ol>
 *
 * <p>Keys are normalised to uppercase strategy codes. Lifecycle is managed by
 * {@code BacktestAsyncRunner} with {@link #enter(Map)} / {@link #exit()} in a
 * try/finally; the bounded backtest executor reuses threads, so a stale entry
 * would poison the next run.
 */
public final class BacktestParamPresetContext {

    private static final ThreadLocal<Map<String, UUID>> HOLDER = new ThreadLocal<>();

    private BacktestParamPresetContext() {
    }

    public static void enter(Map<String, UUID> paramIdsByStrategy) {
        if (paramIdsByStrategy == null || paramIdsByStrategy.isEmpty()) {
            HOLDER.remove();
            return;
        }
        Map<String, UUID> normalised = new HashMap<>();
        for (Map.Entry<String, UUID> e : paramIdsByStrategy.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            normalised.put(e.getKey().trim().toUpperCase(), e.getValue());
        }
        if (normalised.isEmpty()) {
            HOLDER.remove();
        } else {
            HOLDER.set(normalised);
        }
    }

    public static void exit() {
        HOLDER.remove();
    }

    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    /**
     * Returns the pinned paramId for the strategy, or {@code null} when nothing
     * is pinned. Lookup is case-insensitive.
     */
    public static UUID forStrategy(String strategyCode) {
        Map<String, UUID> all = HOLDER.get();
        if (all == null || strategyCode == null) return null;
        return all.get(strategyCode.trim().toUpperCase());
    }
}
