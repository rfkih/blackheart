package id.co.blackheart.engine;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Forward-migrates a {@link StrategySpec} to the engine's supported version.
 *
 * <p>{@link StrategyExecutorFactory} delegates here when an older spec lands
 * against a newer engine. The adapter applies a chain of {@link SpecMigration}
 * steps registered as Spring beans — one step per (archetype, fromVersion)
 * pair, executed in ascending order until the spec's
 * {@code archetypeVersion} equals the engine's {@code supportedVersion()}.
 *
 * <p>Specs newer than the engine are still rejected at the call site — that's
 * a deployment ordering bug, not a migration scenario.
 *
 * <p>Empty by design at M3: the four current archetypes are all at v1. The
 * adapter is the seam future schema bumps (e.g. trend_pullback v2 with a
 * different param shape) plug into without touching the executor factory.
 */
@Component
@Slf4j
public class SpecVersionAdapter {

    /** archetype (lowercased) → fromVersion → migration. At most one migration per (archetype, fromVersion). */
    private final Map<String, Map<Integer, SpecMigration>> chain = new HashMap<>();

    private final List<SpecMigration> migrations;

    public SpecVersionAdapter(List<SpecMigration> migrations) {
        this.migrations = migrations == null ? List.of() : migrations;
    }

    @PostConstruct
    void index() {
        for (SpecMigration m : migrations) {
            String key = normalize(m.archetype());
            int from = m.fromVersion();
            Map<Integer, SpecMigration> perArchetype = chain.computeIfAbsent(key, k -> new HashMap<>());
            SpecMigration prior = perArchetype.put(from, m);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two SpecMigration beans claim (archetype=" + key + ", fromVersion=" + from + "): "
                                + prior.getClass().getName() + " and " + m.getClass().getName());
            }
            log.info("Registered SpecMigration archetype={} fromVersion={}→{} bean={}",
                    key, from, from + 1, m.getClass().getSimpleName());
        }
    }

    /**
     * Walk the migration chain until the spec's archetype version equals
     * {@code targetVersion}. Returns the input spec unchanged if it is
     * already at target. Throws {@link IllegalStateException} if a step is
     * missing or a migration produced an unexpected version (defence against
     * a buggy migration that forgets to bump the version).
     */
    public StrategySpec upgrade(StrategySpec spec, int targetVersion) {
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        Integer current = spec.getArchetypeVersion();
        int v = current == null ? 1 : current;
        if (v == targetVersion) return spec;
        if (v > targetVersion) {
            throw new IllegalStateException(
                    "Spec for code=" + spec.getStrategyCode()
                            + " is at version " + v + " but engine supports only " + targetVersion
                            + " — downgrade is not supported.");
        }

        String archetypeKey = normalize(spec.getArchetype());
        Map<Integer, SpecMigration> perArchetype = chain.getOrDefault(archetypeKey, Map.of());

        StrategySpec working = spec;
        List<Integer> applied = new ArrayList<>();
        while (v < targetVersion) {
            SpecMigration step = perArchetype.get(v);
            if (step == null) {
                throw new IllegalStateException(
                        "No SpecMigration registered for archetype=" + archetypeKey
                                + " fromVersion=" + v
                                + " — cannot bridge spec for code=" + spec.getStrategyCode()
                                + " to engine version " + targetVersion);
            }
            StrategySpec next = step.migrate(working);
            Integer nextVer = next == null ? null : next.getArchetypeVersion();
            int expected = v + 1;
            if (nextVer == null || nextVer != expected) {
                throw new IllegalStateException(
                        "SpecMigration " + step.getClass().getName()
                                + " produced version=" + nextVer + " (expected " + expected + ")");
            }
            applied.add(v);
            working = next;
            v = expected;
        }
        log.info("Upgraded spec code={} archetype={} via steps={} → version={}",
                spec.getStrategyCode(), archetypeKey, applied, targetVersion);
        return working;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
