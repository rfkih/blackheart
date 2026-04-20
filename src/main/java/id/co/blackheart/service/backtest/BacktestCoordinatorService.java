package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.BaseStrategyContext;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTradePosition;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.strategy.StrategyContextEnrichmentService;
import id.co.blackheart.service.strategy.StrategyExecutor;
import id.co.blackheart.service.strategy.StrategyExecutorFactory;
import id.co.blackheart.service.tradelistener.TradeListenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestCoordinatorService {

    private static final String MONITOR_INTERVAL = "5m";
    private static final String EXECUTION_SOURCE = "backtest";

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final StrategyExecutorFactory strategyExecutorFactory;
    private final StrategyContextEnrichmentService strategyContextEnrichmentService;
    private final TradeListenerService tradeListenerService;
    private final BacktestTradeExecutorService backtestTradeExecutorService;
    private final BacktestMetricsService backtestMetricsService;
    private final BacktestPositionSnapshotMapper backtestPositionSnapshotMapper;
    private final BacktestStateService backtestStateService;
    private final BacktestPersistenceService backtestPersistenceService;
    private final BacktestEquityPointRecorder backtestEquityPointRecorder;

    public BacktestExecutionSummary execute(BacktestRun backtestRun) {
        validateBacktestRun(backtestRun);

        String strategyInterval = backtestRun.getInterval();

        List<MarketData> monitorCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                MONITOR_INTERVAL,
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<MarketData> strategyCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                strategyInterval,
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<FeatureStore> strategyFeatures = featureStoreRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                strategyInterval,
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        if (monitorCandles == null || monitorCandles.isEmpty()) {
            throw new IllegalArgumentException("No monitor market data found for interval: " + MONITOR_INTERVAL);
        }

        if (strategyCandles == null || strategyCandles.isEmpty()) {
            throw new IllegalArgumentException("No strategy market data found for interval: " + strategyInterval);
        }

        if (strategyFeatures == null || strategyFeatures.isEmpty()) {
            throw new IllegalArgumentException("No feature store found for interval: " + strategyInterval);
        }

        Map<LocalDateTime, MarketData> strategyCandleByEndTime = strategyCandles.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        MarketData::getEndTime,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        Map<LocalDateTime, FeatureStore> strategyFeatureByStartTime = strategyFeatures.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        FeatureStore::getStartTime,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        List<FeatureStore> sortedStrategyFeatures = strategyFeatures.stream()
                .filter(f -> f != null && f.getStartTime() != null)
                .sorted(Comparator.comparing(FeatureStore::getStartTime))
                .toList();

        BacktestState state = BacktestState.initial(backtestRun);

        List<StrategyExecutorEntry> executors = resolveStrategyExecutors(backtestRun);
        StrategyRequirements requirements = executors.size() == 1
                ? executors.getFirst().executor().getRequirements()
                : mergeRequirements(executors.stream()
                        .map(e -> e.executor().getRequirements())
                        .toList());

        BiasData biasData = preloadBiasData(backtestRun, requirements);

        for (MarketData monitorCandle : monitorCandles) {
            backtestTradeExecutorService.fillPendingEntry(
                    backtestRun, state, monitorCandle.getOpenPrice(), monitorCandle.getStartTime()
            );

            boolean anyPositionClosed = handleListenerStep(backtestRun, state, monitorCandle);

            // Run strategy even when a position was closed if there are still open positions
            // (e.g. TP1 fired on TP1_RUNNER — runner needs UPDATE_POSITION_MANAGEMENT on same bar).
            // When all positions closed via listener, skip strategy to avoid same-bar re-entry.
            if (!anyPositionClosed || hasOpenTrade(state)) {
                handleStrategyStep(
                        backtestRun,
                        state,
                        strategyInterval,
                        strategyCandleByEndTime,
                        strategyFeatureByStartTime,
                        sortedStrategyFeatures,
                        monitorCandle,
                        executors,
                        requirements,
                        biasData
                );
            }

            backtestStateService.checkIntraBarDrawdown(state, monitorCandle.getLowPrice());
            backtestStateService.checkIntraBarDrawdown(state, monitorCandle.getHighPrice());
            backtestStateService.updateEquityAndDrawdown(state, monitorCandle.getClosePrice());
            backtestEquityPointRecorder.record(state, backtestRun, monitorCandle);
        }

        MarketData finalCandle = monitorCandles.getLast();

        forceCloseRemainingOpenTrade(backtestRun, state, finalCandle);
        backtestStateService.updateEquityAndDrawdown(state, finalCandle.getClosePrice());

        state.setEquityPoints(new ArrayList<>(state.getEquityPointIndex().values()));

        BacktestExecutionSummary summary = backtestMetricsService.buildSummary(backtestRun, state);

        backtestPersistenceService.persist(backtestRun, state, summary, true);

        return summary;
    }

    private boolean handleListenerStep(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle
    ) {
        if (state.getActiveTrade() == null
                || state.getActiveTradePositions() == null
                || state.getActiveTradePositions().isEmpty()) {
            return false;
        }

        boolean anyClosed = false;

        for (BacktestTradePosition position : state.getActiveTradePositions()) {
            if (!"OPEN".equalsIgnoreCase(position.getStatus())) {
                continue;
            }

            updatePriceExtremes(position, monitorCandle);
            updateTrailingStop(position);

            PositionSnapshot snapshot = backtestPositionSnapshotMapper.toSnapshot(position);

            ListenerContext listenerContext = ListenerContext.builder()
                    .asset(backtestRun.getAsset())
                    .interval(MONITOR_INTERVAL)
                    .positionSnapshot(snapshot)
                    .latestPrice(monitorCandle.getClosePrice())
                    .candleOpen(monitorCandle.getOpenPrice())
                    .candleHigh(monitorCandle.getHighPrice())
                    .candleLow(monitorCandle.getLowPrice())
                    .build();

            ListenerDecision listenerDecision = tradeListenerService.evaluate(listenerContext);

            if (!listenerDecision.isTriggered()) {
                continue;
            }

            backtestTradeExecutorService.closeSinglePositionFromListener(
                    backtestRun,
                    state,
                    position,
                    listenerDecision.getExitPrice(),
                    listenerDecision.getExitReason(),
                    monitorCandle.getEndTime()
            );

            anyClosed = true;
        }

        return anyClosed;
    }

    private void handleStrategyStep(
            BacktestRun backtestRun,
            BacktestState state,
            String strategyInterval,
            Map<LocalDateTime, MarketData> strategyCandleByEndTime,
            Map<LocalDateTime, FeatureStore> strategyFeatureByStartTime,
            List<FeatureStore> sortedStrategyFeatures,
            MarketData monitorCandle,
            List<StrategyExecutorEntry> executors,
            StrategyRequirements requirements,
            BiasData biasData
    ) {
        MarketData strategyCandle = strategyCandleByEndTime.get(monitorCandle.getEndTime());
        if (strategyCandle == null) {
            return;
        }

        FeatureStore strategyFeature = strategyFeatureByStartTime.get(strategyCandle.getStartTime());
        if (strategyFeature == null) {
            log.warn(
                    "FeatureStore missing for symbol={} interval={} startTime={}",
                    backtestRun.getAsset(),
                    strategyInterval,
                    strategyCandle.getStartTime()
            );
            return;
        }

        PositionSnapshot positionSnapshot = buildPositionSnapshot(state);
        int openPositionCount = countOpenPositions(state);
        boolean hasOpenTrade = hasOpenTrade(state);

        Map<String, Object> executionMetadata = new HashMap<>();
        executionMetadata.put("source", EXECUTION_SOURCE);
        executionMetadata.put("backtestRunId", backtestRun.getBacktestRunId());
        executionMetadata.put("hasOpenTrade", hasOpenTrade);

        if (executors.size() == 1) {
            // ── Single-strategy path (unchanged behaviour) ────────────────────
            AccountStrategy syntheticAs = buildSyntheticAccountStrategy(backtestRun, executors.getFirst().code());
            EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                    backtestRun, state, strategyInterval, strategyCandle, strategyFeature,
                    positionSnapshot, openPositionCount, hasOpenTrade, executionMetadata,
                    syntheticAs, requirements, biasData, sortedStrategyFeatures, monitorCandle);

            StrategyDecision decision = executors.getFirst().executor().execute(enrichedContext);
            log.debug("Strategy decision reason={}", decision.getReason());
            backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);

        } else {
            // ── Multi-strategy orchestrator path ──────────────────────────────
            if (hasOpenTrade) {
                // Delegate only to the strategy that opened the current trade.
                String ownerCode = state.getActiveTrade() != null
                        ? state.getActiveTrade().getStrategyName()
                        : null;

                StrategyExecutorEntry ownerEntry = findExecutorByCode(executors, ownerCode);
                if (ownerEntry == null) {
                    log.warn("Orchestrator: owner strategy code={} not found in executor list, holding", ownerCode);
                    return;
                }

                AccountStrategy syntheticAs = buildSyntheticAccountStrategy(backtestRun, ownerEntry.code());
                StrategyRequirements ownerRequirements = ownerEntry.executor().getRequirements();
                EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                        backtestRun, state, strategyInterval, strategyCandle, strategyFeature,
                        positionSnapshot, openPositionCount, hasOpenTrade, executionMetadata,
                        syntheticAs, ownerRequirements, biasData, sortedStrategyFeatures, monitorCandle);

                StrategyDecision decision = ownerEntry.executor().execute(enrichedContext);
                log.debug("Orchestrator active-trade | owner={} reason={}", ownerEntry.code(), decision.getReason());
                backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);

            } else {
                // Fan-out: try each strategy in declared order; first entry signal wins.
                for (StrategyExecutorEntry entry : executors) {
                    AccountStrategy syntheticAs = buildSyntheticAccountStrategy(backtestRun, entry.code());
                    StrategyRequirements entryRequirements = entry.executor().getRequirements();
                    EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                            backtestRun, state, strategyInterval, strategyCandle, strategyFeature,
                            positionSnapshot, openPositionCount, hasOpenTrade, executionMetadata,
                            syntheticAs, entryRequirements, biasData, sortedStrategyFeatures, monitorCandle);

                    StrategyDecision decision = entry.executor().execute(enrichedContext);

                    if (isEntryDecision(decision)) {
                        // Stamp the winning strategy's code on the decision so the trade records it.
                        if (decision.getStrategyCode() == null || decision.getStrategyCode().isBlank()) {
                            decision.setStrategyCode(entry.code());
                        }
                        log.debug("Orchestrator entry | winner={} side={}", entry.code(), decision.getSide());
                        backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);
                        break; // first signal wins — stop evaluating remaining strategies
                    }
                }
            }
        }
    }

    /**
     * Builds a BaseStrategyContext, enriches it, and applies bias + previousFeatureStore.
     * Extracted to avoid duplicating this block across single/multi-strategy paths.
     */
    private EnrichedStrategyContext buildAndEnrichContext(
            BacktestRun backtestRun,
            BacktestState state,
            String strategyInterval,
            MarketData strategyCandle,
            FeatureStore strategyFeature,
            PositionSnapshot positionSnapshot,
            int openPositionCount,
            boolean hasOpenTrade,
            Map<String, Object> executionMetadata,
            AccountStrategy syntheticAs,
            StrategyRequirements requirements,
            BiasData biasData,
            List<FeatureStore> sortedStrategyFeatures,
            MarketData monitorCandle
    ) {
        BaseStrategyContext baseContext = BaseStrategyContext.builder()
                .account(null)
                .accountStrategy(syntheticAs)
                .asset(backtestRun.getAsset())
                .interval(strategyInterval)
                .marketData(strategyCandle)
                .featureStore(strategyFeature)
                .positionSnapshot(positionSnapshot)
                .hasOpenPosition(positionSnapshot != null && positionSnapshot.isHasOpenPosition())
                .openPositionCount(openPositionCount)
                .executionMetadata(executionMetadata)
                .cashBalance(state.getCashBalance())
                .assetBalance(resolveSyntheticAssetBalanceForBacktest(state, strategyCandle))
                .allowLong(resolveAllowLong(backtestRun))
                .allowShort(resolveAllowShort(backtestRun))
                .maxOpenPositions(resolveMaxOpenPositions(backtestRun))
                .currentOpenTradeCount(hasOpenTrade ? 1 : 0)
                .riskPerTradePct(backtestRun.getRiskPerTradePct())
                .build();

        EnrichedStrategyContext enrichedContext =
                strategyContextEnrichmentService.enrich(baseContext, requirements);

        if (requirements != null && requirements.isRequireBiasTimeframe()) {
            MarketData resolvedBiasMarket = resolveLatestCompletedBiasCandle(
                    biasData.biasCandles(), monitorCandle.getEndTime());
            enrichedContext.setBiasMarketData(resolvedBiasMarket);
            if (resolvedBiasMarket != null) {
                enrichedContext.setBiasFeatureStore(
                        biasData.biasFeatureByStartTime().get(resolvedBiasMarket.getStartTime()));
            }
        }

        if (requirements != null && requirements.isRequirePreviousFeatureStore()) {
            enrichedContext.setPreviousFeatureStore(
                    resolvePreviousFeatureStore(sortedStrategyFeatures, strategyCandle.getStartTime()));
        }

        return enrichedContext;
    }

    private boolean isEntryDecision(StrategyDecision decision) {
        if (decision == null || decision.getDecisionType() == null) return false;
        return decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_LONG
                || decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_SHORT;
    }

    private BiasData preloadBiasData(BacktestRun backtestRun, StrategyRequirements requirements) {
        if (requirements == null
                || !requirements.isRequireBiasTimeframe()
                || requirements.getBiasInterval() == null
                || requirements.getBiasInterval().isBlank()) {
            return new BiasData(List.of(), Map.of());
        }

        List<MarketData> biasCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                requirements.getBiasInterval(),
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<FeatureStore> biasFeatures = featureStoreRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                requirements.getBiasInterval(),
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        Map<LocalDateTime, FeatureStore> biasFeatureByStartTime = biasFeatures == null
                ? Map.of()
                : biasFeatures.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        FeatureStore::getStartTime,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        List<MarketData> sortedBiasCandles = biasCandles == null
                ? List.of()
                : biasCandles.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(MarketData::getEndTime))
                .toList();

        return new BiasData(sortedBiasCandles, biasFeatureByStartTime);
    }

    private FeatureStore resolvePreviousFeatureStore(
            List<FeatureStore> sortedFeatures,
            LocalDateTime currentStartTime
    ) {
        if (sortedFeatures == null || sortedFeatures.isEmpty() || currentStartTime == null) {
            return null;
        }
        FeatureStore prev = null;
        for (FeatureStore f : sortedFeatures) {
            if (f.getStartTime().isBefore(currentStartTime)) {
                prev = f;
            } else {
                break;
            }
        }
        return prev;
    }

    private MarketData resolveLatestCompletedBiasCandle(
            List<MarketData> biasCandles,
            LocalDateTime currentTime
    ) {
        if (biasCandles == null || biasCandles.isEmpty() || currentTime == null) {
            return null;
        }

        return biasCandles.stream()
                .filter(candle -> candle.getEndTime() != null && !candle.getEndTime().isAfter(currentTime))
                .max(Comparator.comparing(MarketData::getEndTime))
                .orElse(null);
    }

    private PositionSnapshot buildPositionSnapshot(BacktestState state) {
        if (state.getActiveTrade() == null
                || state.getActiveTradePositions() == null
                || state.getActiveTradePositions().isEmpty()) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        List<BacktestTradePosition> openPositions = state.getActiveTradePositions().stream()
                .filter(p -> "OPEN".equalsIgnoreCase(p.getStatus()))
                .toList();

        if (openPositions.isEmpty()) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        return backtestPositionSnapshotMapper.toSnapshot(state.getActiveTrade(), openPositions);
    }

    private int countOpenPositions(BacktestState state) {
        if (state == null || state.getActiveTradePositions() == null || state.getActiveTradePositions().isEmpty()) {
            return 0;
        }

        return (int) state.getActiveTradePositions().stream()
                .filter(p -> "OPEN".equalsIgnoreCase(p.getStatus()))
                .count();
    }

    private boolean hasOpenTrade(BacktestState state) {
        return state != null
                && state.getActiveTrade() != null
                && state.getActiveTradePositions() != null
                && state.getActiveTradePositions().stream().anyMatch(p -> "OPEN".equalsIgnoreCase(p.getStatus()));
    }

    private String resolveStrategyLookupCode(BacktestRun backtestRun) {
        if (backtestRun.getStrategyCode() != null && !backtestRun.getStrategyCode().isBlank()) {
            return backtestRun.getStrategyCode();
        }
        return backtestRun.getStrategyName();
    }

    /**
     * Parses the (possibly comma-separated) strategyCode field into an ordered list.
     * "LSR_V2,VCB" → ["LSR_V2", "VCB"]
     * "LSR_V2"     → ["LSR_V2"]
     */
    private List<String> resolveStrategyCodeList(BacktestRun backtestRun) {
        String raw = resolveStrategyLookupCode(backtestRun);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("No strategy code could be resolved from BacktestRun");
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * Resolves a {@link StrategyExecutorEntry} for every strategy code in the run,
     * preserving the declared order (= priority order for orchestrator fan-out).
     */
    private List<StrategyExecutorEntry> resolveStrategyExecutors(BacktestRun backtestRun) {
        return resolveStrategyCodeList(backtestRun).stream()
                .map(code -> new StrategyExecutorEntry(code, strategyExecutorFactory.get(code)))
                .toList();
    }

    /**
     * Merges multiple {@link StrategyRequirements} using OR-logic for boolean flags.
     * The first non-null bias interval is used.
     */
    private StrategyRequirements mergeRequirements(List<StrategyRequirements> list) {
        if (list == null || list.isEmpty()) {
            return StrategyRequirements.builder().build();
        }
        if (list.size() == 1) {
            return list.getFirst();
        }

        boolean biasTimeframe          = list.stream().anyMatch(r -> r != null && r.isRequireBiasTimeframe());
        boolean regimeSnapshot         = list.stream().anyMatch(r -> r != null && r.isRequireRegimeSnapshot());
        boolean volatilitySnapshot     = list.stream().anyMatch(r -> r != null && r.isRequireVolatilitySnapshot());
        boolean riskSnapshot           = list.stream().anyMatch(r -> r != null && r.isRequireRiskSnapshot());
        boolean marketQualitySnapshot  = list.stream().anyMatch(r -> r != null && r.isRequireMarketQualitySnapshot());
        boolean previousFeatureStore   = list.stream().anyMatch(r -> r != null && r.isRequirePreviousFeatureStore());

        String biasInterval = list.stream()
                .filter(r -> r != null && r.getBiasInterval() != null && !r.getBiasInterval().isBlank())
                .map(StrategyRequirements::getBiasInterval)
                .findFirst()
                .orElse(null);

        return StrategyRequirements.builder()
                .requireBiasTimeframe(biasTimeframe)
                .biasInterval(biasInterval)
                .requireRegimeSnapshot(regimeSnapshot)
                .requireVolatilitySnapshot(volatilitySnapshot)
                .requireRiskSnapshot(riskSnapshot)
                .requireMarketQualitySnapshot(marketQualitySnapshot)
                .requirePreviousFeatureStore(previousFeatureStore)
                .build();
    }

    private StrategyExecutorEntry findExecutorByCode(List<StrategyExecutorEntry> executors, String code) {
        if (code == null || executors == null) return null;
        return executors.stream()
                .filter(e -> code.equalsIgnoreCase(e.code()))
                .findFirst()
                .orElse(null);
    }

    /** Pairs a strategy code with its resolved executor. */
    private record StrategyExecutorEntry(String code, StrategyExecutor executor) {}

    private Boolean resolveAllowLong(BacktestRun backtestRun) {
        return backtestRun.getAllowLong() == null ? Boolean.TRUE : backtestRun.getAllowLong();
    }

    private Boolean resolveAllowShort(BacktestRun backtestRun) {
        return backtestRun.getAllowShort() == null ? Boolean.TRUE : backtestRun.getAllowShort();
    }

    private Integer resolveMaxOpenPositions(BacktestRun backtestRun) {
        return backtestRun.getMaxOpenPositions() == null ? 1 : backtestRun.getMaxOpenPositions();
    }

    private AccountStrategy buildSyntheticAccountStrategy(BacktestRun backtestRun, String strategyCode) {
        UUID resolvedId = (backtestRun.getStrategyAccountStrategyIds() != null
                && backtestRun.getStrategyAccountStrategyIds().containsKey(strategyCode))
                ? backtestRun.getStrategyAccountStrategyIds().get(strategyCode)
                : backtestRun.getAccountStrategyId();

        return AccountStrategy.builder()
                .accountStrategyId(resolvedId)
                .strategyCode(strategyCode)
                .intervalName(backtestRun.getInterval())
                .allowLong(resolveAllowLong(backtestRun))
                .allowShort(resolveAllowShort(backtestRun))
                .maxOpenPositions(resolveMaxOpenPositions(backtestRun))
                .enabled(Boolean.TRUE)
                .build();
    }

    private void forceCloseRemainingOpenTrade(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData finalCandle
    ) {
        if (state.getActiveTrade() == null
                || state.getActiveTradePositions() == null
                || state.getActiveTradePositions().isEmpty()
                || finalCandle == null) {
            return;
        }

        for (BacktestTradePosition position : state.getActiveTradePositions()) {
            if (!"OPEN".equalsIgnoreCase(position.getStatus())) {
                continue;
            }

            backtestTradeExecutorService.closeSinglePositionFromListener(
                    backtestRun,
                    state,
                    position,
                    finalCandle.getClosePrice(),
                    "FORCED_END_OF_BACKTEST_CLOSE",
                    finalCandle.getEndTime()
            );
        }
    }

    private BigDecimal resolveSyntheticAssetBalanceForBacktest(
            BacktestState state,
            MarketData strategyCandle
    ) {
        if (state == null || strategyCandle == null || strategyCandle.getClosePrice() == null) {
            return BigDecimal.ZERO;
        }

        if (state.getCashBalance() == null || state.getCashBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        if (strategyCandle.getClosePrice().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return state.getCashBalance()
                .divide(strategyCandle.getClosePrice(), 12, RoundingMode.DOWN);
    }

    private void validateBacktestRun(BacktestRun backtestRun) {
        if (backtestRun == null) {
            throw new IllegalArgumentException("Backtest run must not be null");
        }

        if (backtestRun.getAsset() == null || backtestRun.getAsset().isBlank()) {
            throw new IllegalArgumentException("Backtest asset must not be blank");
        }

        if (backtestRun.getInterval() == null || backtestRun.getInterval().isBlank()) {
            throw new IllegalArgumentException("Backtest interval must not be blank");
        }

        if (backtestRun.getStartTime() == null || backtestRun.getEndTime() == null) {
            throw new IllegalArgumentException("Backtest startTime and endTime must not be null");
        }

        if (!backtestRun.getStartTime().isBefore(backtestRun.getEndTime())) {
            throw new IllegalArgumentException("Backtest startTime must be before endTime");
        }
    }

    /**
     * Updates trailingStopPrice to follow price in the favourable direction,
     * maintaining the fixed offset established between entryPrice and initialTrailingStopPrice.
     * Must be called after updatePriceExtremes so highestPriceSinceEntry / lowestPriceSinceEntry are current.
     */
    private void updateTrailingStop(BacktestTradePosition position) {
        if (position.getTrailingStopPrice() == null
                || position.getInitialTrailingStopPrice() == null
                || position.getEntryPrice() == null) {
            return;
        }

        if ("LONG".equalsIgnoreCase(position.getSide())) {
            BigDecimal highestPrice = position.getHighestPriceSinceEntry();
            if (highestPrice == null) {
                return;
            }
            BigDecimal offset = position.getEntryPrice().subtract(position.getInitialTrailingStopPrice());
            if (offset.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            BigDecimal newTrailing = highestPrice.subtract(offset);
            if (newTrailing.compareTo(position.getTrailingStopPrice()) > 0) {
                position.setTrailingStopPrice(newTrailing);
            }
        } else if ("SHORT".equalsIgnoreCase(position.getSide())) {
            BigDecimal lowestPrice = position.getLowestPriceSinceEntry();
            if (lowestPrice == null) {
                return;
            }
            BigDecimal offset = position.getInitialTrailingStopPrice().subtract(position.getEntryPrice());
            if (offset.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            BigDecimal newTrailing = lowestPrice.add(offset);
            if (newTrailing.compareTo(position.getTrailingStopPrice()) < 0) {
                position.setTrailingStopPrice(newTrailing);
            }
        }
    }

    private void updatePriceExtremes(BacktestTradePosition position, MarketData candle) {
        if ("LONG".equalsIgnoreCase(position.getSide())) {
            // Track both best (for trailing/BE) and worst (for MAE) intrabar prices
            if (candle.getHighPrice() != null
                    && (position.getHighestPriceSinceEntry() == null
                    || candle.getHighPrice().compareTo(position.getHighestPriceSinceEntry()) > 0)) {
                position.setHighestPriceSinceEntry(candle.getHighPrice());
            }
            if (candle.getLowPrice() != null
                    && (position.getLowestPriceSinceEntry() == null
                    || candle.getLowPrice().compareTo(position.getLowestPriceSinceEntry()) < 0)) {
                position.setLowestPriceSinceEntry(candle.getLowPrice());
            }
        } else if ("SHORT".equalsIgnoreCase(position.getSide())) {
            if (candle.getLowPrice() != null
                    && (position.getLowestPriceSinceEntry() == null
                    || candle.getLowPrice().compareTo(position.getLowestPriceSinceEntry()) < 0)) {
                position.setLowestPriceSinceEntry(candle.getLowPrice());
            }
            if (candle.getHighPrice() != null
                    && (position.getHighestPriceSinceEntry() == null
                    || candle.getHighPrice().compareTo(position.getHighestPriceSinceEntry()) > 0)) {
                position.setHighestPriceSinceEntry(candle.getHighPrice());
            }
        }
    }

    private record BiasData(
            List<MarketData> biasCandles,
            Map<LocalDateTime, FeatureStore> biasFeatureByStartTime
    ) {
    }
}