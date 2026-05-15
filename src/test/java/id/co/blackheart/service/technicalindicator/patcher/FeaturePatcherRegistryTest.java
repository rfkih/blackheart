package id.co.blackheart.service.technicalindicator.patcher;

import id.co.blackheart.model.FeatureStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry is the gatekeeper between user-supplied {@code params.column} and
 * the dynamic SQL paths in {@link FeaturePatcherService}. These tests lock
 * in the validation invariants that keep that surface safe.
 */
class FeaturePatcherRegistryTest {

    @Test
    void registers_validPatcher_findReturnsIt() {
        FakePatcher slope = new FakePatcher("slope_200");
        FeaturePatcherRegistry registry = new FeaturePatcherRegistry(List.of(slope));

        assertTrue(registry.find("slope_200").isPresent());
        assertSame(slope, registry.find("slope_200").orElseThrow());
        assertEquals(Set.of("slope_200"), registry.registeredColumns());
    }

    @Test
    void duplicateColumn_failsFastAtConstruction() {
        FakePatcher a = new FakePatcher("slope_200");
        FakePatcher b = new FakePatcher("slope_200");
        List<FeaturePatcher<?>> patchers = List.of(a, b);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new FeaturePatcherRegistry(patchers)
        );
        assertTrue(ex.getMessage().contains("Duplicate"));
        assertTrue(ex.getMessage().contains("slope_200"));
    }

    /**
     * Every rejection path (uppercase, SQL-injection-shaped, leading digit,
     * null) hits the same SAFE_IDENT validation in {@code FeaturePatcherRegistry}
     * and surfaces an {@code IllegalStateException} mentioning the regex.
     * Parameterized so a future "yet another invalid form" lands in one
     * place instead of growing the test count.
     */
    @ParameterizedTest(name = "rejects invalid column: \"{0}\"")
    @NullSource
    @ValueSource(strings = {"Slope_200", "col; DROP TABLE x", "200_slope"})
    void invalidColumn_rejected(String badColumn) {
        FakePatcher bad = new FakePatcher(badColumn);
        List<FeaturePatcher<?>> patchers = List.of(bad);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new FeaturePatcherRegistry(patchers)
        );
        assertTrue(ex.getMessage().contains("[a-z_][a-z0-9_]*"));
    }

    @Test
    void emptyRegistry_findReturnsEmpty() {
        FeaturePatcherRegistry registry = new FeaturePatcherRegistry(List.of());
        assertFalse(registry.find("slope_200").isPresent());
        assertEquals(Set.of(), registry.registeredColumns());
    }

    @Test
    void find_nullColumn_returnsEmpty() {
        FeaturePatcherRegistry registry = new FeaturePatcherRegistry(
                List.of(new FakePatcher("slope_200"))
        );
        assertFalse(registry.find(null).isPresent());
    }

    /** Minimal patcher stub — we only care about {@code primaryColumn()}. */
    private static final class FakePatcher extends FeaturePatcher<Object> {
        private final String column;

        FakePatcher(String column) {
            this.column = column;
        }

        @Override
        public String primaryColumn() {
            return column;
        }

        @Override
        public Object buildAux(String symbol, String interval,
                               LocalDateTime windowStart, LocalDateTime windowEnd) {
            return null;
        }

        @Override
        public PatchOutcome patchRow(FeatureStore row, Object aux) {
            return PatchOutcome.NOT_FILLED;
        }
    }
}
