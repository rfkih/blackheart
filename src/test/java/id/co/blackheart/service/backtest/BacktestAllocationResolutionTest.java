package id.co.blackheart.service.backtest;

import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.service.strategy.StrategyExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the per-strategy allocation resolution chain (Phase A + Fix B + Fix G).
 * <p>
 * Exercises {@link BacktestCoordinatorService#resolveAllocationsForRun} in
 * isolation. The resolver is the single source of truth for "what allocation
 * % does each strategy get for this run?" — once resolved, it's read by
 * the per-tick {@code buildSyntheticAccountStrategy} as a map-only lookup
 * (no DB hits on the hot path).
 */
@ExtendWith(MockitoExtension.class)
class BacktestAllocationResolutionTest {

    @Mock private AccountStrategyRepository accountStrategyRepository;
    @Mock private StrategyExecutor stubExecutor;

    private BacktestCoordinatorService coordinator;

    @BeforeEach
    void setUp() {
        // Most fields are unused by resolveAllocationsForRun — only the
        // accountStrategyRepository matters. Pass nulls for the rest;
        // the method under test never touches them.
        coordinator = new BacktestCoordinatorService(
                null, // marketDataRepository
                null, // featureStoreRepository
                null, // strategyExecutorFactory
                null, // strategyContextEnrichmentService
                null, // tradeListenerService
                null, // backtestTradeExecutorService
                null, // backtestMetricsService
                null, // backtestPositionSnapshotMapper
                null, // backtestStateService
                null, // backtestPersistenceService
                null, // backtestEquityPointRecorder
                null, // progressTracker
                accountStrategyRepository
        );
    }

    private BacktestCoordinatorService.StrategyExecutorEntry entry(String code) {
        return new BacktestCoordinatorService.StrategyExecutorEntry(code, stubExecutor);
    }

    @Test
    void wizardOverrideWinsOverPersistentValue() {
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);
        Map<String, BigDecimal> overrides = new LinkedHashMap<>();
        overrides.put("LSR", new BigDecimal("42"));   // wizard override
        run.setStrategyAllocations(overrides);

        // Persistent value present but should be IGNORED in favour of override.
        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("80"));
        lenient().when(accountStrategyRepository.findById(any())).thenReturn(Optional.of(persisted));

        Map<String, BigDecimal> resolved = coordinator.resolveAllocationsForRun(run, List.of(entry("LSR")));

        assertEquals(new BigDecimal("42"), resolved.get("LSR"));
        // Cache discipline: when the override wins, we don't hit the DB.
        verify(accountStrategyRepository, never()).findById(any());
    }

    @Test
    void persistentValueUsedWhenWizardOverrideAbsent() {
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("65"));
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BigDecimal> resolved = coordinator.resolveAllocationsForRun(run, List.of(entry("VCB")));

        assertEquals(new BigDecimal("65"), resolved.get("VCB"));
    }

    @Test
    void explicitZeroPersistentValueIsRespected() {
        // Fix G — a user who set capital_allocation_pct = 0 means "no
        // capital for this strategy". The resolver MUST NOT treat zero as
        // "fall through to default 100".
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(BigDecimal.ZERO);
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BigDecimal> resolved = coordinator.resolveAllocationsForRun(run, List.of(entry("VCB")));

        assertEquals(BigDecimal.ZERO, resolved.get("VCB"));
    }

    @Test
    void hundredPercentFallbackWhenNeitherSourceHasValue() {
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);

        AccountStrategy persisted = new AccountStrategy();
        // capitalAllocationPct intentionally null — "never set"
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BigDecimal> resolved = coordinator.resolveAllocationsForRun(run, List.of(entry("VCB")));

        assertEquals(new BigDecimal("100"), resolved.get("VCB"));
    }

    @Test
    void wizardKeysAreCaseInsensitive() {
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(UUID.randomUUID());
        // Canonicalised keys are uppercase per BacktestService.canonicaliseAllocations.
        Map<String, BigDecimal> overrides = new LinkedHashMap<>();
        overrides.put("LSR", new BigDecimal("30"));
        run.setStrategyAllocations(overrides);

        // Executor code arrives as lowercase — must still match the upper key.
        Map<String, BigDecimal> resolved = coordinator.resolveAllocationsForRun(run, List.of(entry("lsr")));

        assertEquals(new BigDecimal("30"), resolved.get("lsr"));
    }

    @Test
    void perStrategyAccountStrategyIdMappingTakesPrecedenceOverDefault() {
        UUID defaultId = UUID.randomUUID();
        UUID lsrSpecificId = UUID.randomUUID();

        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(defaultId);
        Map<String, UUID> idMap = new LinkedHashMap<>();
        idMap.put("LSR", lsrSpecificId);
        run.setStrategyAccountStrategyIds(idMap);

        AccountStrategy lsrPersisted = new AccountStrategy();
        lsrPersisted.setCapitalAllocationPct(new BigDecimal("25"));
        when(accountStrategyRepository.findById(lsrSpecificId)).thenReturn(Optional.of(lsrPersisted));

        Map<String, BigDecimal> resolved = coordinator.resolveAllocationsForRun(run, List.of(entry("LSR")));

        assertEquals(new BigDecimal("25"), resolved.get("LSR"));
        verify(accountStrategyRepository).findById(lsrSpecificId);
        verify(accountStrategyRepository, never()).findById(defaultId);
    }

    @Test
    void allocationResolvedOncePerStrategyAtRunStart() {
        // Fix B — caching contract: even if buildSyntheticAccountStrategy
        // is called thousands of times during the run, findById must fire
        // ONLY at run-start during resolveAllocationsForRun.
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("50"));
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        coordinator.resolveAllocationsForRun(run, List.of(entry("LSR"), entry("VCB"), entry("TPR")));

        // Three strategies, all sharing the default account-strategy id.
        // The resolver hits the DB once per strategy (3 calls) — not 3 ×
        // candle-count, which is what the pre-fix code would have done if
        // we'd called buildSyntheticAccountStrategy on the hot path.
        verify(accountStrategyRepository, org.mockito.Mockito.times(3)).findById(accountStrategyId);
    }

    @Test
    void unknownStrategyFallsThroughToDefaultId() {
        UUID defaultId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(defaultId);
        // strategyAccountStrategyIds present but doesn't list this code.
        run.setStrategyAccountStrategyIds(new LinkedHashMap<>());

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("17"));
        when(accountStrategyRepository.findById(defaultId)).thenReturn(Optional.of(persisted));

        Map<String, BigDecimal> resolved = coordinator.resolveAllocationsForRun(run, List.of(entry("GHOST")));

        assertEquals(new BigDecimal("17"), resolved.get("GHOST"));
    }
}
