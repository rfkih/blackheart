package id.co.blackheart.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpecVersionAdapterTest {

    @Test
    void noOpWhenAlreadyAtTargetVersion() {
        SpecVersionAdapter adapter = build();   // no migrations registered
        StrategySpec spec = spec("MRO", "mean_reversion_oscillator", 1);

        StrategySpec out = adapter.upgrade(spec, 1);

        assertSame(spec, out, "unchanged spec must be returned by reference");
    }

    @Test
    void downgradeRejected() {
        SpecVersionAdapter adapter = build();
        StrategySpec spec = spec("MRO", "mean_reversion_oscillator", 3);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adapter.upgrade(spec, 1));
        assert ex.getMessage().contains("downgrade is not supported");
    }

    @Test
    void missingStepProducesActionableError() {
        SpecVersionAdapter adapter = build();   // no migrations
        StrategySpec spec = spec("MRO", "mean_reversion_oscillator", 1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adapter.upgrade(spec, 2));
        assert ex.getMessage().contains("No SpecMigration registered");
        assert ex.getMessage().contains("fromVersion=1");
    }

    @Test
    void singleStepUpgradeBumpsVersionAndApplies() {
        SpecMigration v1to2 = bumper("mean_reversion_oscillator", 1,
                params -> params.put("addedAtV2", true));
        SpecVersionAdapter adapter = build(v1to2);

        StrategySpec spec = spec("MRO", "mean_reversion_oscillator", 1);
        StrategySpec out = adapter.upgrade(spec, 2);

        assertEquals(2, out.getArchetypeVersion());
        assertEquals(Boolean.TRUE, out.getParams().get("addedAtV2"));
    }

    @Test
    void chainOfMigrationsAppliesInOrder() {
        SpecMigration v1to2 = bumper("trend_pullback", 1, p -> p.put("step1", "v2"));
        SpecMigration v2to3 = bumper("trend_pullback", 2, p -> p.put("step2", "v3"));
        SpecVersionAdapter adapter = build(v2to3, v1to2);   // unordered registration

        StrategySpec spec = spec("TPR", "trend_pullback", 1);
        StrategySpec out = adapter.upgrade(spec, 3);

        assertEquals(3, out.getArchetypeVersion());
        assertEquals("v2", out.getParams().get("step1"));
        assertEquals("v3", out.getParams().get("step2"));
    }

    @Test
    void duplicateMigrationsForSameStepAreRejectedAtBoot() {
        SpecMigration a = bumper("mean_reversion_oscillator", 1, p -> {});
        SpecMigration b = bumper("mean_reversion_oscillator", 1, p -> {});

        assertThrows(IllegalStateException.class, () -> build(a, b));
    }

    @Test
    void migrationThatForgetsToBumpVersionIsRejected() {
        SpecMigration buggy = new SpecMigration() {
            public String archetype() { return "donchian_breakout"; }
            public int fromVersion() { return 1; }
            public StrategySpec migrate(StrategySpec source) {
                return StrategySpec.builder()
                        .strategyCode(source.getStrategyCode())
                        .archetype(source.getArchetype())
                        .archetypeVersion(source.getArchetypeVersion())   // ← forgot to bump
                        .specSchemaVersion(source.getSpecSchemaVersion())
                        .params(source.getParams())
                        .body(source.getBody())
                        .build();
            }
        };
        SpecVersionAdapter adapter = build(buggy);
        StrategySpec spec = spec("DCB", "donchian_breakout", 1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adapter.upgrade(spec, 2));
        assert ex.getMessage().contains("produced version=1");
    }

    @Test
    void unknownArchetypeFailsLoudlyWhenUpgradeNeeded() {
        SpecVersionAdapter adapter = build();
        StrategySpec spec = spec("XYZ", "unknown_archetype", 1);

        assertThrows(IllegalStateException.class, () -> adapter.upgrade(spec, 2));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static SpecVersionAdapter build(SpecMigration... migrations) {
        SpecVersionAdapter adapter = new SpecVersionAdapter(List.of(migrations));
        adapter.index();
        return adapter;
    }

    private static StrategySpec spec(String code, String archetype, int version) {
        return StrategySpec.builder()
                .strategyCode(code)
                .strategyName(code)
                .archetype(archetype)
                .archetypeVersion(version)
                .specSchemaVersion(1)
                .params(new HashMap<>())
                .body(new HashMap<>())
                .build();
    }

    /** Build a migration whose only effect is to apply a Map mutation to params and bump version. */
    private static SpecMigration bumper(String archetype, int from,
                                        java.util.function.Consumer<Map<String, Object>> mutate) {
        return new SpecMigration() {
            public String archetype() { return archetype; }
            public int fromVersion() { return from; }
            public StrategySpec migrate(StrategySpec source) {
                Map<String, Object> nextParams = source.getParams() == null
                        ? new HashMap<>() : new HashMap<>(source.getParams());
                mutate.accept(nextParams);
                return StrategySpec.builder()
                        .strategyCode(source.getStrategyCode())
                        .strategyName(source.getStrategyName())
                        .archetype(source.getArchetype())
                        .archetypeVersion(source.getArchetypeVersion() + 1)
                        .specSchemaVersion(source.getSpecSchemaVersion())
                        .params(nextParams)
                        .body(source.getBody())
                        .build();
            }
        };
    }
}
