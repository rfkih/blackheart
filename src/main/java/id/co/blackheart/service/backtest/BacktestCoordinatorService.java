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

        BacktestState state = BacktestState.initial(backtestRun);

        StrategyExecutor executor = strategyExecutorFactory.get(resolveStrategyLookupCode(backtestRun));
        StrategyRequirements requirements = executor.getRequirements();

        BiasData biasData = preloadBiasData(backtestRun, requirements);

        for (MarketData monitorCandle : monitorCandles) {
            backtestTradeExecutorService.fillPendingEntry(
                    backtestRun, state, monitorCandle.getOpenPrice(), monitorCandle.getStartTime()
            );

            boolean anyPositionClosed = handleListenerStep(backtestRun, state, monitorCandle);

            if (!anyPositionClosed) {
                handleStrategyStep(
                        backtestRun,
                        state,
                        strategyInterval,
                        strategyCandleByEndTime,
                        strategyFeatureByStartTime,
                        monitorCandle,
                        executor,
                        requirements,
                        biasData
                );
            }

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

            PositionSnapshot snapshot = backtestPositionSnapshotMapper.toSnapshot(position);

            ListenerContext listenerContext = ListenerContext.builder()
                    .asset(backtestRun.getAsset())
                    .interval(MONITOR_INTERVAL)
                    .positionSnapshot(snapshot)
                    .latestPrice(monitorCandle.getClosePrice())
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
            MarketData monitorCandle,
            StrategyExecutor executor,
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

        AccountStrategy syntheticAccountStrategy = buildSyntheticAccountStrategy(backtestRun);

        Map<String, Object> executionMetadata = new HashMap<>();
        executionMetadata.put("source", EXECUTION_SOURCE);
        executionMetadata.put("backtestRunId", backtestRun.getBacktestRunId());
        executionMetadata.put("hasOpenTrade", hasOpenTrade);

        BaseStrategyContext baseContext = BaseStrategyContext.builder()
                .account(null)
                .accountStrategy(syntheticAccountStrategy)
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

        MarketData resolvedBiasMarket = null;
        FeatureStore resolvedBiasFeature = null;

        if (requirements != null && requirements.isRequireBiasTimeframe()) {
            resolvedBiasMarket = resolveLatestCompletedBiasCandle(
                    biasData.biasCandles(),
                    monitorCandle.getEndTime()
            );

            if (resolvedBiasMarket != null) {
                resolvedBiasFeature = biasData.biasFeatureByStartTime().get(resolvedBiasMarket.getStartTime());
            }
        }

        enrichedContext.setBiasMarketData(resolvedBiasMarket);
        enrichedContext.setBiasFeatureStore(resolvedBiasFeature);

        StrategyDecision decision = executor.execute(enrichedContext);

        log.debug("Strategy decision reason={}", decision.getReason());
        log.debug("Strategy decision side={}", decision.getSide());

        backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);
    }

    private BiasData preloadBiasData(BacktestRun backtestRun, StrategyRequirements requirements) {
        if (requirements == null
                || !Boolean.TRUE.equals(requirements.isRequireBiasTimeframe())
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

    private Boolean resolveAllowLong(BacktestRun backtestRun) {
        return backtestRun.getAllowLong() == null ? Boolean.TRUE : backtestRun.getAllowLong();
    }

    private Boolean resolveAllowShort(BacktestRun backtestRun) {
        return backtestRun.getAllowShort() == null ? Boolean.TRUE : backtestRun.getAllowShort();
    }

    private Integer resolveMaxOpenPositions(BacktestRun backtestRun) {
        return backtestRun.getMaxOpenPositions() == null ? 1 : backtestRun.getMaxOpenPositions();
    }

    private AccountStrategy buildSyntheticAccountStrategy(BacktestRun backtestRun) {
        return AccountStrategy.builder()
                .accountStrategyId(backtestRun.getAccountStrategyId())
                .strategyCode(resolveStrategyLookupCode(backtestRun))
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

    private record BiasData(
            List<MarketData> biasCandles,
            Map<LocalDateTime, FeatureStore> biasFeatureByStartTime
    ) {
    }
}