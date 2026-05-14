package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.build.BuildInfoService;
import id.co.blackheart.service.risk.SlippageCalibrationService;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Locks in the V62 request → entity round-trip for the four risk-gate override
 * maps. Without these assertions, a regression like the one caught in the
 * end-to-end audit (BacktestService.create dropped the maps on the floor)
 * would ship silently — the API would accept the wizard's override JSON, the
 * persistence layer would write NULL, and the backtest engine's gate
 * evaluator would see no overrides regardless of what the operator selected.
 *
 * <p>Each test captures the {@link BacktestRun} entity built inside
 * {@link BacktestService#runBacktest} via the {@link BacktestConcurrencyGate}
 * call seam, then asserts on the persisted shape.
 */
@ExtendWith(MockitoExtension.class)
class BacktestServiceGateOverridesTest {

    @Mock private BacktestRunRepository backtestRunRepository;
    @Mock private BacktestResponseMapper backtestMapperService;
    @Mock private AccountStrategyOwnershipGuard ownershipGuard;
    @Mock private BacktestConcurrencyGate concurrencyGate;
    @Mock private BacktestKafkaProducer kafkaProducer;
    @Mock private BuildInfoService buildInfoService;
    @Mock private SlippageCalibrationService slippageCalibrationService;
    @Mock private BacktestDataValidatorService backtestDataValidatorService;

    private BacktestService service;

    @BeforeEach
    void setUp() {
        // Real ObjectMapper — serialiseOverrides uses it; mocking it would
        // require fragile stub gymnastics for no test value.
        service = new BacktestService(
                backtestRunRepository,
                backtestMapperService,
                ownershipGuard,
                concurrencyGate,
                kafkaProducer,
                new ObjectMapper(),
                buildInfoService,
                slippageCalibrationService,
                backtestDataValidatorService);

        // Lenient stubs because not every test path reaches each one (e.g.
        // validation-only failure tests wouldn't fire kafkaProducer).
        lenient().when(buildInfoService.getGitCommitSha()).thenReturn("test-sha");
        lenient().when(buildInfoService.getAppVersion()).thenReturn("test-1.0");
        lenient().when(slippageCalibrationService.calibratedRateAsFraction(any()))
                .thenReturn(Optional.empty());
        // concurrencyGate.checkAndSave returns the row it was handed — the
        // call seam where the entity is "persisted" in the test.
        lenient().when(concurrencyGate.checkAndSave(any())).thenAnswer(inv -> inv.getArgument(0));
        // Mapper return value is not asserted on; any non-null response works.
        lenient().when(backtestMapperService.toRunResponse(any()))
                .thenReturn(new BacktestRunResponse());
    }

    private BacktestRunRequest baseRequest() {
        BacktestRunRequest r = new BacktestRunRequest();
        r.setAccountStrategyId(UUID.randomUUID());
        r.setStrategyCodes(List.of("LSR"));
        r.setAsset("BTCUSDT");
        r.setInterval("1h");
        r.setStartTime(LocalDateTime.of(2024, 1, 1, 0, 0));
        r.setEndTime(LocalDateTime.of(2024, 2, 1, 0, 0));
        r.setInitialCapital(new BigDecimal("10000"));
        return r;
    }

    private BacktestRun captureSavedRun() {
        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(concurrencyGate).checkAndSave(captor.capture());
        return captor.getValue();
    }

    @Test
    void killSwitchOverrideMapPersistedOnEntity() {
        // Regression guard for the end-to-end audit's bug #1 — wizard sends
        // a gate override, persistence drops it. Without the four
        // .strategy*Overrides(...) builder calls added in V62 follow-up,
        // r.getStrategyKillSwitchOverrides() would be null even though the
        // request carried the map.
        BacktestRunRequest req = baseRequest();
        req.setStrategyKillSwitchOverrides(Map.of("LSR", Boolean.TRUE));

        service.runBacktest(UUID.randomUUID(), req);
        BacktestRun saved = captureSavedRun();

        assertNotNull(saved.getStrategyKillSwitchOverrides(),
                "wizard kill-switch override must reach the persisted entity, not be dropped on the floor");
        assertEquals(Boolean.TRUE, saved.getStrategyKillSwitchOverrides().get("LSR"));
    }

    @Test
    void allFourGateOverrideMapsPersistedOnEntity() {
        // Verifies the four V62 fields are independently plumbed. A
        // copy-paste bug that wired only the first three would fail here.
        BacktestRunRequest req = baseRequest();
        req.setStrategyKillSwitchOverrides(Map.of("LSR", Boolean.TRUE));
        req.setStrategyRegimeOverrides(Map.of("LSR", Boolean.FALSE));
        req.setStrategyCorrelationOverrides(Map.of("LSR", Boolean.TRUE));
        req.setStrategyConcurrentCapOverrides(Map.of("LSR", Boolean.FALSE));

        service.runBacktest(UUID.randomUUID(), req);
        BacktestRun saved = captureSavedRun();

        assertEquals(Boolean.TRUE,  saved.getStrategyKillSwitchOverrides().get("LSR"));
        assertEquals(Boolean.FALSE, saved.getStrategyRegimeOverrides().get("LSR"));
        assertEquals(Boolean.TRUE,  saved.getStrategyCorrelationOverrides().get("LSR"));
        assertEquals(Boolean.FALSE, saved.getStrategyConcurrentCapOverrides().get("LSR"));
    }

    @Test
    void gateOverrideKeysAreCanonicalisedToUppercase() {
        // BacktestCoordinatorService.resolveBoolOverride does
        // code.toUpperCase() on lookup. If the persisted map stored the
        // wizard's mixed-case key verbatim, the lookup would miss and the
        // override would silently not apply. canonicaliseStrategyBoolMap
        // upper-cases on save — locking that in here.
        BacktestRunRequest req = baseRequest();
        req.setStrategyKillSwitchOverrides(Map.of("lsr", Boolean.TRUE));

        service.runBacktest(UUID.randomUUID(), req);
        BacktestRun saved = captureSavedRun();

        Map<String, Boolean> persisted = saved.getStrategyKillSwitchOverrides();
        assertEquals(Boolean.TRUE, persisted.get("LSR"),
                "gate override keys must be upper-cased so resolveBoolOverride's toUpperCase lookup hits");
        assertNull(persisted.get("lsr"),
                "lowercase key from the wizard must NOT survive into the persisted map");
    }

    @Test
    void absentGateOverridesPersistAsNullNotEmptyMap() {
        // Request supplies no overrides at all (the common case). Persisted
        // entity should carry null for each map so the JSONB column stays
        // SQL NULL — not '{}' which would still trigger a JSON parse on
        // every read and complicate "did the operator override anything?"
        // queries. nullIfEmpty enforces this.
        BacktestRunRequest req = baseRequest();
        // No setStrategy*Overrides calls — all four are absent.

        service.runBacktest(UUID.randomUUID(), req);
        BacktestRun saved = captureSavedRun();

        assertNull(saved.getStrategyKillSwitchOverrides(),
                "absent kill-switch overrides must NOT round-trip as an empty map");
        assertNull(saved.getStrategyRegimeOverrides());
        assertNull(saved.getStrategyCorrelationOverrides());
        assertNull(saved.getStrategyConcurrentCapOverrides());
    }

    @Test
    void explicitFalseGateOverridePreserved() {
        // "Force off" (false) is a meaningful override value distinct from
        // "no override" (absent). The trim and canonicalisation must keep
        // both true and false on the way through.
        BacktestRunRequest req = baseRequest();
        req.setStrategyCorrelationOverrides(Map.of("LSR", Boolean.FALSE));

        service.runBacktest(UUID.randomUUID(), req);
        BacktestRun saved = captureSavedRun();

        assertNotNull(saved.getStrategyCorrelationOverrides());
        assertEquals(Boolean.FALSE, saved.getStrategyCorrelationOverrides().get("LSR"));
    }
}
