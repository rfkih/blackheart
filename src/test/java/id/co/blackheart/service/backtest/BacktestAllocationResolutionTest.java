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
        // resolveAllocationsForRun only touches accountStrategyRepository, so
        // every other ctor dep is nulled out — the method under test never
        // dereferences them.
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
                accountStrategyRepository,
                null, // riskGuardService (V62 — gate stack; resolveAllocationsForRun doesn't touch it)
                null  // accountRepository (V62 — same)
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

        // Persistent allocation present but should be IGNORED in favour of override.
        // V55 — even when override wins for allocation, the persisted row is
        // still consulted because useRiskBasedSizing/riskPct have no wizard
        // overrides; the DB is the single source of truth for those fields so
        // the synthetic AccountStrategy mirrors the live row's sizing model.
        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("80"));
        when(accountStrategyRepository.findById(any())).thenReturn(Optional.of(persisted));

        Map<String, BigDecimal> resolved = coordinator.resolveAllocationsForRun(run, List.of(entry("LSR")));

        assertEquals(new BigDecimal("42"), resolved.get("LSR"));
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

    // ── V55 — risk-based sizing propagation ─────────────────────────────────

    @Test
    void sizingResolverPropagatesRiskFieldsFromPersistedRow() {
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("60"));
        persisted.setUseRiskBasedSizing(Boolean.TRUE);
        persisted.setRiskPct(new BigDecimal("0.0300"));
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("LSR")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("LSR");
        assertEquals(new BigDecimal("60"), s.allocationPct());
        assertEquals(Boolean.TRUE, s.useRiskBasedSizing());
        assertEquals(new BigDecimal("0.0300"), s.riskPct());
    }

    @Test
    void sizingResolverPullsRiskFieldsEvenWhenWizardOverridesAllocation() {
        // Critical V55 contract — the synthetic AS in backtest must mirror the
        // live row's risk-based sizing toggle even when the wizard rewrites
        // allocation. Otherwise the operator's backtest of a risk-based
        // strategy would silently fall through to legacy direct sizing because
        // the wizard-override path used to short-circuit the DB lookup.
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);
        Map<String, BigDecimal> overrides = new LinkedHashMap<>();
        overrides.put("LSR", new BigDecimal("42"));
        run.setStrategyAllocations(overrides);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("80"));
        persisted.setUseRiskBasedSizing(Boolean.TRUE);
        persisted.setRiskPct(new BigDecimal("0.0500"));
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("LSR")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("LSR");
        assertEquals(new BigDecimal("42"), s.allocationPct());        // wizard override wins
        assertEquals(Boolean.TRUE, s.useRiskBasedSizing());            // pulled from persisted
        assertEquals(new BigDecimal("0.0500"), s.riskPct());           // pulled from persisted
    }

    @Test
    void sizingResolverDefaultsToLegacyWhenPersistedRowMissing() {
        // No persisted row → legacy fallback (FALSE / 5%) so backtests on
        // accounts without a DB-resident AccountStrategy preserve the pre-V55
        // direct-allocation sizing path.
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(UUID.randomUUID());

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("LSR")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("LSR");
        assertEquals(Boolean.FALSE, s.useRiskBasedSizing());
        assertEquals(new BigDecimal("0.0500"), s.riskPct());
    }

    @Test
    void sizingResolverDefaultsNullToggleAndRiskFromPersistedRow() {
        // Pre-V55 rows may have null in the new columns until the migration
        // backfills them. Defensive: treat null as FALSE / 0.05 so we don't
        // NPE in StrategyHelper.calculateLongEntryNotional.
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("40"));
        // useRiskBasedSizing + riskPct intentionally null
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("VCB")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("VCB");
        assertEquals(new BigDecimal("40"), s.allocationPct());
        assertEquals(Boolean.FALSE, s.useRiskBasedSizing());
        assertEquals(new BigDecimal("0.0500"), s.riskPct());
    }

    // ── V57 — wizard riskPct override ───────────────────────────────────

    @Test
    void wizardRiskOverrideForcesRiskBasedSizingOnAndOverridesPersistedRiskPct() {
        // V57 contract — setting strategyRiskPcts in the wizard payload
        // explicitly overrides account_strategy.risk_pct AND forces
        // useRiskBasedSizing on for that strategy in the run, even if
        // the persisted toggle is off. Lets operators do "what if" risk
        // comparisons without persistently flipping the live row.
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);
        Map<String, BigDecimal> riskOverrides = new LinkedHashMap<>();
        riskOverrides.put("LSR", new BigDecimal("0.0300"));
        run.setStrategyRiskPcts(riskOverrides);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("50"));
        persisted.setUseRiskBasedSizing(Boolean.FALSE);   // off persistently
        persisted.setRiskPct(new BigDecimal("0.0500"));   // 5% persistently
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("LSR")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("LSR");
        assertEquals(new BigDecimal("50"), s.allocationPct());
        assertEquals(Boolean.TRUE, s.useRiskBasedSizing());          // forced on
        assertEquals(new BigDecimal("0.0300"), s.riskPct());          // wizard wins
    }

    @Test
    void wizardRiskOverrideMissingKeyFallsBackToPersistedRiskPct() {
        // Strategies without an entry in strategyRiskPcts inherit the
        // persisted risk_pct + persisted toggle as before.
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);
        Map<String, BigDecimal> riskOverrides = new LinkedHashMap<>();
        riskOverrides.put("LSR", new BigDecimal("0.0300"));
        run.setStrategyRiskPcts(riskOverrides);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("60"));
        persisted.setUseRiskBasedSizing(Boolean.TRUE);
        persisted.setRiskPct(new BigDecimal("0.0200"));
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("VCB")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("VCB");
        assertEquals(new BigDecimal("60"), s.allocationPct());
        assertEquals(Boolean.TRUE, s.useRiskBasedSizing());
        assertEquals(new BigDecimal("0.0200"), s.riskPct());
    }

    @Test
    void wizardRiskOverrideAppliesEvenWhenNoPersistedRowExists() {
        // Audit fix — when no persisted row resolves (ad-hoc run with no
        // accountStrategyId), the wizard riskOverride is still honoured
        // rather than silently dropped. Before the fix, the legacy fallback
        // path ignored riskOverride and fell back to direct allocation.
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(null);
        Map<String, BigDecimal> riskOverrides = new LinkedHashMap<>();
        riskOverrides.put("LSR", new BigDecimal("0.0400"));
        run.setStrategyRiskPcts(riskOverrides);

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("LSR")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("LSR");
        assertEquals(Boolean.TRUE, s.useRiskBasedSizing());
        assertEquals(new BigDecimal("0.0400"), s.riskPct());
    }

    @Test
    void wizardRiskOverrideKeysAreCaseInsensitive() {
        // Mirror of allocation override — wizard keys arrive uppercased
        // by canonicaliseStrategyRiskPcts; executor codes can come in any
        // case and still match.
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(UUID.randomUUID());
        Map<String, BigDecimal> riskOverrides = new LinkedHashMap<>();
        riskOverrides.put("LSR", new BigDecimal("0.0300"));
        run.setStrategyRiskPcts(riskOverrides);

        // Lowercase executor code — must still match the canonicalised key.
        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("lsr")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("lsr");
        assertEquals(Boolean.TRUE, s.useRiskBasedSizing());
        assertEquals(new BigDecimal("0.0300"), s.riskPct());
    }

    // ── V62 — gate-toggle + kill-switch-state propagation ──────────────────

    @Test
    void sizingResolverPropagatesAllFourGateTogglesFromPersistedRow() {
        // Each gate flag flows independently from the persisted row to the
        // synthetic AccountStrategy via StrategySizing. Without this, the
        // backtest gate evaluator would see all-false and apply no gates
        // even when the live row has them on.
        UUID accountStrategyId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setCapitalAllocationPct(new BigDecimal("50"));
        persisted.setKillSwitchGateEnabled(Boolean.TRUE);
        persisted.setRegimeGateEnabled(Boolean.FALSE);
        persisted.setCorrelationGateEnabled(Boolean.TRUE);
        persisted.setConcurrentCapGateEnabled(Boolean.FALSE);
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("LSR")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("LSR");
        assertEquals(Boolean.TRUE,  s.killSwitchGateEnabled());
        assertEquals(Boolean.FALSE, s.regimeGateEnabled());
        assertEquals(Boolean.TRUE,  s.correlationGateEnabled());
        assertEquals(Boolean.FALSE, s.concurrentCapGateEnabled());
    }

    @Test
    void sizingResolverPropagatesKillSwitchTrippedStateAndAccountId() {
        // V62 review-fix #1 — kill-switch RUNTIME state (tripped flag +
        // reason) must travel through to the synthetic AS so a backtest
        // launched against a currently-tripped live strategy honours that
        // state when the kill-switch gate is enabled.
        // V62 review-fix #3 — accountId pre-resolved so the backtest gate
        // evaluator can read Account from a state-level cache rather than
        // re-issuing an accountStrategyRepository.findById per entry.
        UUID accountStrategyId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(accountStrategyId);

        AccountStrategy persisted = new AccountStrategy();
        persisted.setAccountId(accountId);
        persisted.setCapitalAllocationPct(new BigDecimal("40"));
        persisted.setKillSwitchGateEnabled(Boolean.TRUE);
        persisted.setIsKillSwitchTripped(Boolean.TRUE);
        persisted.setKillSwitchReason("30-day DD 32.4% reached threshold 25.00%");
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(persisted));

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("LSR")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("LSR");
        assertEquals(Boolean.TRUE, s.isKillSwitchTripped(),
                "tripped flag must propagate so backtest kill-switch gate denies in parity with live");
        assertEquals("30-day DD 32.4% reached threshold 25.00%", s.killSwitchReason());
        assertEquals(accountId, s.accountId(),
                "accountId must propagate so gate evaluator can resolve Account from cache");
    }

    @Test
    void sizingResolverDefaultsLegacyWhenPersistedRowMissingNullsTripFields() {
        // Unbound run: legacy fallback. Tripped=false, reason=null, accountId
        // null. The backtest gate evaluator sees a null accountId and
        // short-circuits to allow (cannot evaluate account-level gates
        // without an account).
        BacktestRun run = new BacktestRun();
        run.setAccountStrategyId(UUID.randomUUID());

        Map<String, BacktestCoordinatorService.StrategySizing> sizing =
                coordinator.resolveStrategySizingForRun(run, List.of(entry("LSR")));

        BacktestCoordinatorService.StrategySizing s = sizing.get("LSR");
        assertEquals(Boolean.FALSE, s.isKillSwitchTripped());
        assertEquals(null, s.killSwitchReason());
        assertEquals(null, s.accountId());
        // Gate toggles also default off.
        assertEquals(Boolean.FALSE, s.killSwitchGateEnabled());
        assertEquals(Boolean.FALSE, s.regimeGateEnabled());
        assertEquals(Boolean.FALSE, s.correlationGateEnabled());
        assertEquals(Boolean.FALSE, s.concurrentCapGateEnabled());
    }
}
