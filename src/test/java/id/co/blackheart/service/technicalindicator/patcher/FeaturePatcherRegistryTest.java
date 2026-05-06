package id.co.blackheart.service.technicalindicator.patcher;

import id.co.blackheart.model.FeatureStore;
import org.junit.jupiter.api.Test;

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

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new FeaturePatcherRegistry(List.of(a, b))
        );
        assertTrue(ex.getMessage().contains("Duplicate"));
        assertTrue(ex.getMessage().contains("slope_200"));
    }

    @Test
    void invalidIdentifier_uppercase_rejected() {
        FakePatcher bad = new FakePatcher("Slope_200");
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new FeaturePatcherRegistry(List.of(bad))
        );
        assertTrue(ex.getMessage().contains("[a-z_][a-z0-9_]*"));
    }

    @Test
    void invalidIdentifier_sqlInjection_rejected() {
        // Nobody would deliberately do this — but the regex is the defense
        // against accidental misuse / a future patcher drift.
        FakePatcher bad = new FakePatcher("col; DROP TABLE x");
        assertThrows(
                IllegalStateException.class,
                () -> new FeaturePatcherRegistry(List.of(bad))
        );
    }

    @Test
    void invalidIdentifier_leadingDigit_rejected() {
        FakePatcher bad = new FakePatcher("200_slope");
        assertThrows(
                IllegalStateException.class,
                () -> new FeaturePatcherRegistry(List.of(bad))
        );
    }

    @Test
    void nullColumn_rejected() {
        FakePatcher bad = new FakePatcher(null);
        assertThrows(
                IllegalStateException.class,
                () -> new FeaturePatcherRegistry(List.of(bad))
        );
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
        public void patchRow(FeatureStore row, Object aux) {
            // no-op
        }
    }
}
