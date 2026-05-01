package id.co.blackheart.engine;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineMetricsTest {

    @Test
    void successesIncrementEvalCountButNeverTrip() {
        RecordingKillSwitch ks = new RecordingKillSwitch();
        EngineMetrics m = new EngineMetrics(ks, 3, 10);

        m.recordSuccess("MMR");
        m.recordSuccess("MMR");
        m.recordSuccess("MMR");

        assertEquals(3, m.getEvalCount("MMR"));
        assertEquals(0, m.getErrorCount("MMR"));
        assertEquals(0, ks.tripCount.get());
    }

    @Test
    void errorsBelowThresholdDoNotTrip() {
        RecordingKillSwitch ks = new RecordingKillSwitch();
        EngineMetrics m = new EngineMetrics(ks, 3, 10);
        UUID asId = UUID.randomUUID();

        m.recordError(asId, "DCB", new RuntimeException("boom"));
        m.recordError(asId, "DCB", new RuntimeException("boom"));

        assertEquals(2, m.getErrorCount("DCB"));
        assertEquals(0, ks.tripCount.get());
    }

    @Test
    void atThresholdTripsExactlyOnce() {
        RecordingKillSwitch ks = new RecordingKillSwitch();
        EngineMetrics m = new EngineMetrics(ks, 3, 10);
        UUID asId = UUID.randomUUID();

        m.recordError(asId, "DCB", new RuntimeException("boom"));
        m.recordError(asId, "DCB", new RuntimeException("boom"));
        m.recordError(asId, "DCB", new RuntimeException("boom"));   // tripping call
        m.recordError(asId, "DCB", new RuntimeException("boom"));   // already tripped
        m.recordError(asId, "DCB", new RuntimeException("boom"));

        assertEquals(1, ks.tripCount.get(), "trip must be sticky per process");
        assertEquals(asId, ks.lastAccountStrategyId.get());
        assertNotNull(ks.lastReason.get());
        assertTrue(ks.lastReason.get().contains("threshold 3"),
                "reason must explain the threshold for the operator");
    }

    @Test
    void differentAccountStrategiesAreIndependent() {
        RecordingKillSwitch ks = new RecordingKillSwitch();
        EngineMetrics m = new EngineMetrics(ks, 2, 10);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        m.recordError(a, "DCB", new RuntimeException("a1"));
        m.recordError(b, "DCB", new RuntimeException("b1"));
        m.recordError(a, "DCB", new RuntimeException("a2"));   // trips a, not b

        assertEquals(1, ks.tripCount.get());
        assertEquals(a, ks.lastAccountStrategyId.get());

        m.recordError(b, "DCB", new RuntimeException("b2"));   // now trips b
        assertEquals(2, ks.tripCount.get());
        assertEquals(b, ks.lastAccountStrategyId.get());
    }

    @Test
    void resetClearsTripStateAndErrorWindow() {
        RecordingKillSwitch ks = new RecordingKillSwitch();
        EngineMetrics m = new EngineMetrics(ks, 2, 10);
        UUID asId = UUID.randomUUID();

        m.recordError(asId, "DCB", new RuntimeException("e1"));
        m.recordError(asId, "DCB", new RuntimeException("e2"));   // first trip
        assertEquals(1, ks.tripCount.get());

        m.reset(asId);

        m.recordError(asId, "DCB", new RuntimeException("e3"));
        assertEquals(1, ks.tripCount.get(), "single error post-reset must not re-trip");
        m.recordError(asId, "DCB", new RuntimeException("e4"));
        assertEquals(2, ks.tripCount.get(), "second error after reset crosses threshold again");
    }

    @Test
    void nullAccountStrategyIdIsIgnoredButCountersAreUpdated() {
        RecordingKillSwitch ks = new RecordingKillSwitch();
        EngineMetrics m = new EngineMetrics(ks, 1, 10);

        m.recordError(null, "DCB", new RuntimeException("boom"));

        assertEquals(1, m.getErrorCount("DCB"), "code-level counter still ticks");
        assertEquals(0, ks.tripCount.get(), "no per-strategy id → nothing to trip");
    }

    @Test
    void errorsOlderThanWindowSlideOff() {
        RecordingKillSwitch ks = new RecordingKillSwitch();
        AtomicLong now = new AtomicLong(0L);
        // 3 errors / 10-minute window; clock starts at t=0.
        EngineMetrics m = new EngineMetrics(ks, 3, 10, now::get);
        UUID asId = UUID.randomUUID();

        m.recordError(asId, "DCB", new RuntimeException("e1"));   // t=0
        now.set(60_000L);
        m.recordError(asId, "DCB", new RuntimeException("e2"));   // t=1m
        // Jump past the 10-minute window; e1 and e2 must slide off.
        now.set(11L * 60_000L);
        m.recordError(asId, "DCB", new RuntimeException("e3"));   // window now holds only e3
        assertEquals(0, ks.tripCount.get(),
                "expired entries must drop off — single in-window error is below threshold");

        m.recordError(asId, "DCB", new RuntimeException("e4"));
        m.recordError(asId, "DCB", new RuntimeException("e5"));
        assertEquals(1, ks.tripCount.get(),
                "three in-window errors after slide-off must trip exactly once");
    }

    @Test
    void nullErrorClassDoesNotBreakReason() {
        RecordingKillSwitch ks = new RecordingKillSwitch();
        EngineMetrics m = new EngineMetrics(ks, 1, 10);
        UUID asId = UUID.randomUUID();

        m.recordError(asId, "DCB", null);

        assertEquals(1, ks.tripCount.get());
        assertNotNull(ks.lastReason.get());
        assertFalse(ks.lastReason.get().contains("null:"),
                "null-error path must produce a clean reason string");
    }

    /** Test double for {@link EngineKillSwitchService} — captures trip calls. */
    private static final class RecordingKillSwitch extends EngineKillSwitchService {
        final AtomicInteger tripCount = new AtomicInteger();
        final AtomicReference<UUID> lastAccountStrategyId = new AtomicReference<>();
        final AtomicReference<String> lastReason = new AtomicReference<>();

        RecordingKillSwitch() {
            // Use protected no-arg ctor — we override the only method that
            // would touch the repository / transaction template.
            super();
        }

        @Override
        public void tripFromEngineErrors(UUID accountStrategyId, String reason) {
            tripCount.incrementAndGet();
            lastAccountStrategyId.set(accountStrategyId);
            lastReason.set(reason);
        }
    }
}
