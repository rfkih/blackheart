package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.strategy.StrategyExecutor;
import id.co.blackheart.service.strategy.StrategyExecutorFactory;
import id.co.blackheart.service.tradelistener.TradeListenerService;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestCoordinatorService {

    private static final String MONITOR_INTERVAL = "15m";
    private static final String BIAS_INTERVAL = "4h";

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final StrategyExecutorFactory strategyExecutorFactory;
    private final TradeListenerService tradeListenerService;
    private final BacktestTradeExecutorService backtestTradeExecutorService;
    private final BacktestMetricsService backtestMetricsService;
    private final BacktestPositionSnapshotMapper backtestPositionSnapshotMapper;
    private final BacktestStateService backtestStateService;

    public BacktestExecutionSummary execute(BacktestRun backtestRun) {
        validateBacktestRun(backtestRun);

        String strategyInterval = backtestRun.getInterval();
        boolean use4hBiasFor15m = MONITOR_INTERVAL.equalsIgnoreCase(strategyInterval);

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

        List<MarketData> biasCandles = List.of();
        Map<LocalDateTime, FeatureStore> biasFeatureByStartTime = Map.of();

        if (use4hBiasFor15m) {
            biasCandles = marketDataRepository.findBySymbolIntervalAndRange(
                    backtestRun.getAsset(),
                    BIAS_INTERVAL,
                    backtestRun.getStartTime(),
                    backtestRun.getEndTime()
            );

            List<FeatureStore> biasFeatures = featureStoreRepository.findBySymbolIntervalAndRange(
                    backtestRun.getAsset(),
                    BIAS_INTERVAL,
                    backtestRun.getStartTime(),
                    backtestRun.getEndTime()
            );

            if (biasCandles == null || biasCandles.isEmpty()) {
                throw new IllegalArgumentException("No bias market data found for interval: " + BIAS_INTERVAL);
            }

            if (biasFeatures == null || biasFeatures.isEmpty()) {
                throw new IllegalArgumentException("No bias feature store found for interval: " + BIAS_INTERVAL);
            }

            biasCandles = biasCandles.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(MarketData::getEndTime))
                    .toList();

            biasFeatureByStartTime = biasFeatures.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(
                            FeatureStore::getStartTime,
                            Function.identity(),
                            (existing, replacement) -> existing
                    ));
        }

        BacktestState state = BacktestState.initial(backtestRun);

        for (MarketData monitorCandle : monitorCandles) {
            boolean closedByListener = handleListenerStep(backtestRun, state, monitorCandle);

            if (!closedByListener) {
                handleStrategyStep(
                        backtestRun,
                        state,
                        strategyInterval,
                        strategyCandleByEndTime,
                        strategyFeatureByStartTime,
                        biasCandles,
                        biasFeatureByStartTime,
                        monitorCandle
                );
            }

            backtestStateService.updateEquityAndDrawdown(state, monitorCandle.getClosePrice());
        }

        return backtestMetricsService.buildSummary(backtestRun, state);
    }

    private boolean handleListenerStep(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle
    ) {
        BacktestTrade activeTrade = state.getActiveTrade();
        if (activeTrade == null || state.getActiveTradePositions() == null || state.getActiveTradePositions().isEmpty()) {
            return false;
        }

        PositionSnapshot positionSnapshot =
                backtestPositionSnapshotMapper.toSnapshot(activeTrade, state.getActiveTradePositions());

        ListenerContext listenerContext = ListenerContext.builder()
                .asset(backtestRun.getAsset())
                .interval(MONITOR_INTERVAL)
                .positionSnapshot(positionSnapshot)
                .latestPrice(monitorCandle.getClosePrice())
                .build();

        ListenerDecision listenerDecision = tradeListenerService.evaluate(listenerContext);

        if (!listenerDecision.isTriggered()) {
            return false;
        }

        StrategyContext listenerCloseContext = StrategyContext.builder()
                .user(null)
                .asset(backtestRun.getAsset())
                .interval(MONITOR_INTERVAL)
                .marketData(monitorCandle)
                .featureStore(null)
                .positionSnapshot(positionSnapshot)
                .build();

        backtestTradeExecutorService.closeTradeFromListener(
                backtestRun,
                state,
                listenerCloseContext,
                listenerDecision.getExitReason(),
                listenerDecision.getExitPrice()
        );

        return true;
    }

    private void handleStrategyStep(
            BacktestRun backtestRun,
            BacktestState state,
            String strategyInterval,
            Map<LocalDateTime, MarketData> strategyCandleByEndTime,
            Map<LocalDateTime, FeatureStore> strategyFeatureByStartTime,
            List<MarketData> biasCandles,
            Map<LocalDateTime, FeatureStore> biasFeatureByStartTime,
            MarketData monitorCandle
    ) {
        MarketData strategyCandle = strategyCandleByEndTime.get(monitorCandle.getEndTime());
        if (strategyCandle == null) {
            return;
        }

        FeatureStore strategyFeature = strategyFeatureByStartTime.get(strategyCandle.getStartTime());
        if (strategyFeature == null) {
            log.warn("FeatureStore missing for symbol={} interval={} startTime={}",
                    backtestRun.getAsset(),
                    strategyInterval,
                    strategyCandle.getStartTime());
            return;
        }

        MarketData biasMarketData = null;
        FeatureStore biasFeatureStore = null;

        if (MONITOR_INTERVAL.equalsIgnoreCase(strategyInterval)) {
            biasMarketData = resolveLatestCompletedBiasCandle(biasCandles, monitorCandle.getEndTime());

            if (biasMarketData == null) {
                log.warn("Bias market data missing for symbol={} biasInterval={} monitorEndTime={}",
                        backtestRun.getAsset(), BIAS_INTERVAL, monitorCandle.getEndTime());
                return;
            }

            biasFeatureStore = biasFeatureByStartTime.get(biasMarketData.getStartTime());
            if (biasFeatureStore == null) {
                log.warn("Bias feature store missing for symbol={} biasInterval={} startTime={}",
                        backtestRun.getAsset(), BIAS_INTERVAL, biasMarketData.getStartTime());
                return;
            }
        }

        PositionSnapshot positionSnapshot;
        if (state.getActiveTrade() == null || state.getActiveTradePositions() == null || state.getActiveTradePositions().isEmpty()) {
            positionSnapshot = PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        } else {
            positionSnapshot = backtestPositionSnapshotMapper.toSnapshot(
                    state.getActiveTrade(),
                    state.getActiveTradePositions()
            );
        }

        StrategyContext strategyContext = StrategyContext.builder()
                .user(null)
                .asset(backtestRun.getAsset())
                .interval(strategyInterval)
                .userStrategyId(backtestRun.getUserStrategyId())
                .strategyCode(backtestRun.getStrategyName())
                .marketData(strategyCandle)
                .featureStore(strategyFeature)
                .cashBalance(state.getCashBalance())
                .assetBalance(state.getAssetBalance())
                .riskPerTradePct(backtestRun.getRiskPerTradePct())
                .biasMarketData(biasMarketData)
                .biasFeatureStore(biasFeatureStore)
                .allowLong(true)
                .allowShort(true)
                .positionSnapshot(positionSnapshot)
                .build();

        StrategyExecutor executor = strategyExecutorFactory.get(backtestRun.getStrategyName());
        StrategyDecision decision = executor.execute(strategyContext);

//        if (decision != null && !DecisionType.HOLD.equals(decision.getDecisionType())) {
//            log.info("Backtest strategy decision | runId={} time={} strategyInterval={} decisionType={} reason={}",
//                    backtestRun.getBacktestRunId(),
//                    strategyCandle.getEndTime(),
//                    strategyInterval,
//                    decision.getDecisionType(),
//                    decision.getReason());
//        }

        backtestTradeExecutorService.execute(backtestRun, state, strategyContext, decision);
    }

    private MarketData resolveLatestCompletedBiasCandle(
            List<MarketData> biasCandles,
            LocalDateTime monitorEndTime
    ) {
        if (biasCandles == null || biasCandles.isEmpty() || monitorEndTime == null) {
            return null;
        }

        MarketData latest = null;
        for (MarketData candle : biasCandles) {
            if (candle.getEndTime() == null) {
                continue;
            }
            if (!candle.getEndTime().isAfter(monitorEndTime)) {
                latest = candle;
            } else {
                break;
            }
        }
        return latest;
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
}