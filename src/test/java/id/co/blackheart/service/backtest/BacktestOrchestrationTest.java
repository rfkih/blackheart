package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.strategy.StrategyExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the multi-interval / multi-strategy live-parity fixes that
 * landed this session:
 * <ul>
 *   <li>Per-strategy bias data isolation (vs the legacy merged-bias bug).
 *   <li>Per-interval-group cap matching live's
 *       {@code LiveOrchestratorCoordinatorService.fanOutForEntry}.
 *   <li>Priority-order parity: executors sorted by
 *       {@code accountStrategy.priorityOrder} ascending.
 * </ul>
 *
 * <p>These are the orchestration helpers the user-visible bug audit
 * surfaced. Without these tests, a future refactor that re-introduces
 * the global-cap or merged-bias semantic would pass typecheck and
 * compile silently. Each test fails loudly on regression.
 */
@ExtendWith(MockitoExtension.class)
class BacktestOrchestrationTest {

    @Mock private MarketDataRepository marketDataRepository;
    @Mock private FeatureStoreRepository featureStoreRepository;
    @Mock private AccountStrategyRepository accountStrategyRepository;
    @Mock private StrategyExecutor lsrExecutor;
    @Mock private StrategyExecutor vcbExecutor;
    @Mock private StrategyExecutor tprExecutor;

    private BacktestCoordinatorService coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new BacktestCoordinatorService(
                marketDataRepository,
                featureStoreRepository,
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

    private BacktestCoordinatorService.StrategyExecutorEntry entry(String code, StrategyExecutor exec) {
        return new BacktestCoordinatorService.StrategyExecutorEntry(code, exec);
    }

    // ───────────────────────────────────────────────────────────────────
    // Per-strategy bias data (Fix #2 from the live-parity audit)
    // ───────────────────────────────────────────────────────────────────

    @Test
    void biasDataIsolatedPerStrategy() {
        // LSR wants 4h bias; VCB wants 1d. The legacy code merged
        // requirements and served LSR's 4h to both.
        StrategyRequirements lsrReq = StrategyRequirements.builder()
                .requireBiasTimeframe(true).biasInterval("4h").build();
        StrategyRequirements vcbReq = StrategyRequirements.builder()
                .requireBiasTimeframe(true).biasInterval("1d").build();
        when(lsrExecutor.getRequirements()).thenReturn(lsrReq);
        when(vcbExecutor.getRequirements()).thenReturn(vcbReq);

        MarketData fourHCandle = candle(LocalDateTime.of(2024, 1, 1, 4, 0));
        MarketData oneDCandle = candle(LocalDateTime.of(2024, 1, 1, 0, 0));
        FeatureStore fourHFeature = feature(LocalDateTime.of(2024, 1, 1, 0, 0));
        FeatureStore oneDFeature = feature(LocalDateTime.of(2024, 1, 1, 0, 0));

        BacktestRun run = run("BTCUSDT");
        when(marketDataRepository.findBySymbolIntervalAndRange(eq("BTCUSDT"), eq("4h"), any(), any()))
                .thenReturn(List.of(fourHCandle));
        when(marketDataRepository.findBySymbolIntervalAndRange(eq("BTCUSDT"), eq("1d"), any(), any()))
                .thenReturn(List.of(oneDCandle));
        when(featureStoreRepository.findBySymbolIntervalAndRange(eq("BTCUSDT"), eq("4h"), any(), any()))
                .thenReturn(List.of(fourHFeature));
        when(featureStoreRepository.findBySymbolIntervalAndRange(eq("BTCUSDT"), eq("1d"), any(), any()))
                .thenReturn(List.of(oneDFeature));

        Map<String, BacktestCoordinatorService.BiasData> result = coordinator.preloadBiasDataPerStrategy(
                run, List.of(entry("LSR", lsrExecutor), entry("VCB", vcbExecutor))
        );

        assertEquals(2, result.size());
        // LSR sees 4h bias only.
        assertEquals(List.of(fourHCandle), result.get("LSR").biasCandles());
        // VCB sees 1d bias only — never the 4h.
        assertEquals(List.of(oneDCandle), result.get("VCB").biasCandles());
    }

    @Test
    void biasDataLoadCachedByInterval() {
        // Two strategies with the SAME bias interval should share one DB load.
        StrategyRequirements req = StrategyRequirements.builder()
                .requireBiasTimeframe(true).biasInterval("4h").build();
        when(lsrExecutor.getRequirements()).thenReturn(req);
        when(vcbExecutor.getRequirements()).thenReturn(req);

        BacktestRun run = run("BTCUSDT");
        when(marketDataRepository.findBySymbolIntervalAndRange(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(candle(LocalDateTime.of(2024, 1, 1, 0, 0))));
        when(featureStoreRepository.findBySymbolIntervalAndRange(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(feature(LocalDateTime.of(2024, 1, 1, 0, 0))));

        coordinator.preloadBiasDataPerStrategy(run,
                List.of(entry("LSR", lsrExecutor), entry("VCB", vcbExecutor)));

        // Cache by biasInterval — exactly ONE call to each repo for "4h".
        verify(marketDataRepository, times(1))
                .findBySymbolIntervalAndRange(eq("BTCUSDT"), eq("4h"), any(), any());
        verify(featureStoreRepository, times(1))
                .findBySymbolIntervalAndRange(eq("BTCUSDT"), eq("4h"), any(), any());
    }

    @Test
    void strategiesWithoutBiasReturnEmpty() {
        // requireBiasTimeframe=false → no DB load, returns empty BiasData.
        StrategyRequirements noBias = StrategyRequirements.builder()
                .requireBiasTimeframe(false).build();
        when(lsrExecutor.getRequirements()).thenReturn(noBias);

        BacktestRun run = run("BTCUSDT");
        Map<String, BacktestCoordinatorService.BiasData> result = coordinator.preloadBiasDataPerStrategy(
                run, List.of(entry("LSR", lsrExecutor)));

        assertEquals(1, result.size());
        assertTrue(result.get("LSR").biasCandles().isEmpty());
        verify(marketDataRepository, never())
                .findBySymbolIntervalAndRange(anyString(), anyString(), any(), any());
    }

    // ───────────────────────────────────────────────────────────────────
    // Per-interval-group cap (live parity — Fix #1)
    // ───────────────────────────────────────────────────────────────────

    @Test
    void intervalGroupBusyWhenAnyStrategyOnIntervalHasActiveTrade() {
        BacktestState state = BacktestState.initial(run("BTCUSDT"));
        // LSR holds an active trade on 15m.
        state.addActiveTrade("LSR", trade("LSR"), List.of());

        Map<String, BacktestCoordinatorService.IntervalContext> contexts = Map.of(
                "LSR", interval15m(),
                "VCB", interval15m(),
                "TPR", interval1h()
        );
        List<BacktestCoordinatorService.StrategyExecutorEntry> executors = List.of(
                entry("LSR", lsrExecutor), entry("VCB", vcbExecutor), entry("TPR", tprExecutor)
        );

        // 15m group is busy because LSR holds it — no second 15m trade allowed.
        assertTrue(coordinator.intervalGroupBusy(state, executors, "15m", contexts));
        // 1h group is free — TPR can still open.
        assertFalse(coordinator.intervalGroupBusy(state, executors, "1h", contexts));
    }

    @Test
    void intervalGroupBusyWhenAnyStrategyOnIntervalHasPendingEntry() {
        BacktestState state = BacktestState.initial(run("BTCUSDT"));
        // VCB just queued a pending entry on 15m.
        state.setPendingEntryFor("VCB", new BacktestState.PendingEntry(
                null, "LONG", null, null, "15m"));

        Map<String, BacktestCoordinatorService.IntervalContext> contexts = Map.of(
                "LSR", interval15m(),
                "VCB", interval15m()
        );
        List<BacktestCoordinatorService.StrategyExecutorEntry> executors = List.of(
                entry("LSR", lsrExecutor), entry("VCB", vcbExecutor)
        );

        // 15m group is busy because VCB has a pending — LSR can't queue too.
        // This is the cap-bypass-via-pending fix (Fix #A from the audit).
        assertTrue(coordinator.intervalGroupBusy(state, executors, "15m", contexts));
    }

    @Test
    void intervalGroupNotBusyWhenAllSlotsFree() {
        BacktestState state = BacktestState.initial(run("BTCUSDT"));
        Map<String, BacktestCoordinatorService.IntervalContext> contexts = Map.of(
                "LSR", interval15m(),
                "VCB", interval15m()
        );
        List<BacktestCoordinatorService.StrategyExecutorEntry> executors = List.of(
                entry("LSR", lsrExecutor), entry("VCB", vcbExecutor)
        );

        assertFalse(coordinator.intervalGroupBusy(state, executors, "15m", contexts));
    }

    // ───────────────────────────────────────────────────────────────────
    // Priority-order parity (Fix #3)
    // ───────────────────────────────────────────────────────────────────

    @Test
    void priorityOrdersResolvedFromPersistentAccountStrategy() {
        UUID lsrId = UUID.randomUUID();
        UUID vcbId = UUID.randomUUID();

        BacktestRun run = run("BTCUSDT");
        Map<String, UUID> idMap = new LinkedHashMap<>();
        idMap.put("LSR", lsrId);
        idMap.put("VCB", vcbId);
        run.setStrategyAccountStrategyIds(idMap);

        when(accountStrategyRepository.findById(lsrId)).thenReturn(Optional.of(asWithPriority(1)));
        when(accountStrategyRepository.findById(vcbId)).thenReturn(Optional.of(asWithPriority(2)));

        Map<String, Integer> priorities = coordinator.resolvePriorityOrders(run, List.of("LSR", "VCB"));

        assertEquals(1, priorities.get("LSR"));
        assertEquals(2, priorities.get("VCB"));
    }

    @Test
    void nullPriorityOrderSortsToEnd() {
        // Strategies whose persistent AS has no priorityOrder must land
        // AFTER explicit priorities — Integer.MAX_VALUE sentinel.
        UUID lsrId = UUID.randomUUID();
        UUID vcbId = UUID.randomUUID();

        BacktestRun run = run("BTCUSDT");
        Map<String, UUID> idMap = new LinkedHashMap<>();
        idMap.put("LSR", lsrId);
        idMap.put("VCB", vcbId);
        run.setStrategyAccountStrategyIds(idMap);

        // LSR has explicit priority 5; VCB has null.
        when(accountStrategyRepository.findById(lsrId)).thenReturn(Optional.of(asWithPriority(5)));
        AccountStrategy vcbNoPri = new AccountStrategy();
        vcbNoPri.setPriorityOrder(null);
        when(accountStrategyRepository.findById(vcbId)).thenReturn(Optional.of(vcbNoPri));

        Map<String, Integer> priorities = coordinator.resolvePriorityOrders(run, List.of("LSR", "VCB"));

        assertEquals(5, priorities.get("LSR"));
        assertEquals(Integer.MAX_VALUE, priorities.get("VCB"));
    }

    @Test
    void unknownAccountStrategyFallsThroughToMaxValue() {
        // No persistent AS row at all: priority defaults to MAX_VALUE so
        // the strategy sorts to the end.
        UUID id = UUID.randomUUID();
        BacktestRun run = run("BTCUSDT");
        run.setAccountStrategyId(id);
        when(accountStrategyRepository.findById(id)).thenReturn(Optional.empty());

        Map<String, Integer> priorities = coordinator.resolvePriorityOrders(run, List.of("GHOST"));
        assertEquals(Integer.MAX_VALUE, priorities.get("GHOST"));
    }

    // ───────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────

    private BacktestRun run(String asset) {
        BacktestRun r = new BacktestRun();
        r.setAsset(asset);
        r.setStartTime(LocalDateTime.of(2024, 1, 1, 0, 0));
        r.setEndTime(LocalDateTime.of(2024, 1, 31, 0, 0));
        r.setInitialCapital(new BigDecimal("10000"));
        return r;
    }

    private MarketData candle(LocalDateTime endTime) {
        MarketData m = new MarketData();
        m.setEndTime(endTime);
        m.setStartTime(endTime.minusHours(4));
        m.setClosePrice(new BigDecimal("50000"));
        return m;
    }

    private FeatureStore feature(LocalDateTime startTime) {
        FeatureStore f = new FeatureStore();
        f.setStartTime(startTime);
        return f;
    }

    private BacktestTrade trade(String code) {
        BacktestTrade t = new BacktestTrade();
        t.setStrategyName(code);
        t.setBacktestTradeId(UUID.randomUUID());
        return t;
    }

    private AccountStrategy asWithPriority(int priority) {
        AccountStrategy a = new AccountStrategy();
        a.setPriorityOrder(priority);
        return a;
    }

    private BacktestCoordinatorService.IntervalContext interval15m() {
        return new BacktestCoordinatorService.IntervalContext("15m", Map.of(), Map.of(), List.of());
    }

    private BacktestCoordinatorService.IntervalContext interval1h() {
        return new BacktestCoordinatorService.IntervalContext("1h", Map.of(), Map.of(), List.of());
    }
}
