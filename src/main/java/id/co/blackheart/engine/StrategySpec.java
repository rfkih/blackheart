package id.co.blackheart.engine;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Typed wrapper around a strategy specification — the spec_jsonb body
 * stored on {@code strategy_definition}, merged with per-account
 * {@code strategy_param.param_overrides} for the running tuple.
 *
 * <p>Thin by design (M2.1). It exposes dot-path lookups and primitive
 * coercions so engines don't reach into raw maps. Later milestones may
 * promote frequently-used fields into typed accessors per archetype.
 *
 * <p>The spec is logically immutable for the duration of one
 * {@link id.co.blackheart.service.strategy.StrategyExecutor#execute}
 * invocation. Callers must not mutate the underlying maps after
 * construction; the helpers in {@link #merge(Map)} return new instances.
 */
@Getter
@Builder
public class StrategySpec {

    /** Strategy code (e.g. {@code "BBR"}). */
    private final String strategyCode;

    /** Human-readable name from {@code strategy_definition.strategy_name}. */
    private final String strategyName;

    /** Archetype name (e.g. {@code "mean_reversion_oscillator"}). */
    private final String archetype;

    /** Archetype-schema version the spec was authored against. */
    private final Integer archetypeVersion;

    /** Top-level envelope version (separate from archetypeVersion — see blueprint §7). */
    private final Integer specSchemaVersion;

    /**
     * Effective params after merging archetype defaults with per-account overrides.
     * Engines read tuning values from here, not from {@link #body}.
     */
    private final Map<String, Object> params;

    /**
     * Raw spec body (entry/sizing/exits/etc.). Engines read structural fields
     * from here. The {@code params} sub-map is intentionally separated into
     * {@link #params} after override merging.
     */
    private final Map<String, Object> body;

    /**
     * Returns a new spec instance with overrides merged on top of {@link #params}.
     * Existing {@link #params} keys are kept when not in {@code overrides}.
     * Empty/null {@code overrides} returns this same instance.
     */
    public StrategySpec merge(Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = params == null ? new HashMap<>() : new HashMap<>(params);
        merged.putAll(overrides);
        return StrategySpec.builder()
                .strategyCode(strategyCode)
                .strategyName(strategyName)
                .archetype(archetype)
                .archetypeVersion(archetypeVersion)
                .specSchemaVersion(specSchemaVersion)
                .params(merged)
                .body(body)
                .build();
    }

    // ── Param accessors ───────────────────────────────────────────────────────

    public BigDecimal paramBigDecimal(String key, BigDecimal fallback) {
        Object v = params == null ? null : params.get(key);
        return Coerce.toBigDecimal(v, fallback);
    }

    public Integer paramInteger(String key, Integer fallback) {
        Object v = params == null ? null : params.get(key);
        return Coerce.toInteger(v, fallback);
    }

    public Boolean paramBoolean(String key, Boolean fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s)  return Boolean.parseBoolean(s);
        return fallback;
    }

    public String paramString(String key, String fallback) {
        Object v = params == null ? null : params.get(key);
        return v == null ? fallback : v.toString();
    }

    // ── Body accessors (dot-path) ─────────────────────────────────────────────

    /**
     * Look up a value at the given dot-path under {@link #body} —
     * e.g. {@code body("entry.band_lower_field")}. Returns {@code null}
     * when any segment is missing or not a map.
     */
    public Object body(String path) {
        Objects.requireNonNull(path, "path");
        if (body == null) return null;
        String[] segments = path.split("\\.");
        Object cursor = body;
        for (String seg : segments) {
            if (!(cursor instanceof Map<?, ?> m)) return null;
            cursor = m.get(seg);
            if (cursor == null) return null;
        }
        return cursor;
    }

    public String bodyString(String path, String fallback) {
        Object v = body(path);
        return v == null ? fallback : v.toString();
    }

    private static final class Coerce {
        static BigDecimal toBigDecimal(Object v, BigDecimal fallback) {
            if (v == null) return fallback;
            if (v instanceof BigDecimal bd) return bd;
            if (v instanceof Number n)      return BigDecimal.valueOf(n.doubleValue());
            if (v instanceof String s) {
                try { return new BigDecimal(s.trim()); }
                catch (NumberFormatException e) { return fallback; }
            }
            return fallback;
        }
        static Integer toInteger(Object v, Integer fallback) {
            if (v == null) return fallback;
            if (v instanceof Integer i)     return i;
            if (v instanceof Number n)      return n.intValue();
            if (v instanceof String s) {
                try { return Integer.parseInt(s.trim()); }
                catch (NumberFormatException e) { return fallback; }
            }
            return fallback;
        }
    }
}
