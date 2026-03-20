package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.repository.UsersRepository;
import id.co.blackheart.service.strategy.TrendFollowingStrategyService;
import id.co.blackheart.service.tradelistener.TradeListenerService;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    private final UsersRepository usersRepository;
    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final TrendFollowingStrategyService trendFollowingStrategyService;
    private final TradeListenerService tradeListenerService;
    private final BacktestTradeExecutorService backtestTradeExecutorService;
    private final BacktestMetricsService backtestMetricsService;
    private final BacktestPositionSnapshotMapper backtestPositionSnapshotMapper;
    private final BacktestStateService backtestStateService;

    public BacktestExecutionSummary execute(BacktestRun backtestRun) {
        validateBacktestRun(backtestRun);

        Users user = usersRepository.findByUserId(backtestRun.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("User not found for id: " + backtestRun.getUserId());
        }

        String strategyInterval = backtestRun.getInterval();

        List<MarketData> monitorCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getSymbol(),
                MONITOR_INTERVAL,
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<MarketData> strategyCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getSymbol(),
                strategyInterval,
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<FeatureStore> strategyFeatures = featureStoreRepository.findBySymbolIntervalAndRange(
                backtestRun.getSymbol(),
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

        for (MarketData monitorCandle : monitorCandles) {
            boolean closedByListener = handleListenerStep(backtestRun, user, state, monitorCandle);

            if (!closedByListener) {
                handleStrategyStep(backtestRun, user, state, strategyInterval, strategyCandleByEndTime,
                        strategyFeatureByStartTime, monitorCandle);
            }

            backtestStateService.updateEquityAndDrawdown(state, monitorCandle.getClosePrice());
        }

        return backtestMetricsService.buildSummary(backtestRun, state);
    }

    private boolean handleListenerStep(
            BacktestRun backtestRun,
            Users user,
            BacktestState state,
            MarketData monitorCandle
    ) {
        if (state.getActiveTrade() == null) {
            return false;
        }

        PositionSnapshot positionSnapshot = backtestPositionSnapshotMapper.toSnapshot(state.getActiveTrade());

        ListenerContext listenerContext = ListenerContext.builder()
                .asset(backtestRun.getSymbol())
                .interval(MONITOR_INTERVAL)
                .positionSnapshot(positionSnapshot)
                .monitorCandle(monitorCandle)
                .build();

        ListenerDecision listenerDecision = tradeListenerService.evaluate(listenerContext);

        if (!listenerDecision.isTriggered()) {
            return false;
        }

        StrategyContext listenerCloseContext = StrategyContext.builder()
                .user(user)
                .asset(backtestRun.getSymbol())
                .interval(MONITOR_INTERVAL)
                .marketData(monitorCandle)
                .featureStore(null)
                .positionSnapshot(positionSnapshot)
                .build();

        log.info("Backtest listener triggered | runId={} time={} exitReason={} exitPrice={}",
                backtestRun.getBacktestRunId(),
                monitorCandle.getEndTime(),
                listenerDecision.getExitReason(),
                listenerDecision.getExitPrice());

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
            Users user,
            BacktestState state,
            String strategyInterval,
            Map<LocalDateTime, MarketData> strategyCandleByEndTime,
            Map<LocalDateTime, FeatureStore> strategyFeatureByStartTime,
            MarketData monitorCandle
    ) {
        MarketData strategyCandle = strategyCandleByEndTime.get(monitorCandle.getEndTime());
        if (strategyCandle == null) {
            return;
        }

        FeatureStore strategyFeature = strategyFeatureByStartTime.get(strategyCandle.getStartTime());
        if (strategyFeature == null) {
            log.warn("FeatureStore missing for symbol={} interval={} startTime={}",
                    backtestRun.getSymbol(),
                    strategyInterval,
                    strategyCandle.getStartTime());
            return;
        }

        PositionSnapshot positionSnapshot = backtestPositionSnapshotMapper.toSnapshot(state.getActiveTrade());

        StrategyContext strategyContext = StrategyContext.builder()
                .user(user)
                .asset(backtestRun.getSymbol())
                .interval(strategyInterval)
                .marketData(strategyCandle)
                .featureStore(strategyFeature)
                .positionSnapshot(positionSnapshot)
                .build();

        StrategyDecision decision = trendFollowingStrategyService.execute(strategyContext);

        if (!DecisionType.HOLD.equals(decision.getDecisionType())) {
            log.info("Backtest strategy decision | runId={} time={} strategyInterval={} decisionType={} reason={}",
                    backtestRun.getBacktestRunId(),
                    strategyCandle.getEndTime(),
                    strategyInterval,
                    decision.getDecisionType(),
                    decision.getReason());
        }

        backtestTradeExecutorService.execute(backtestRun, state, strategyContext, decision);
    }

    private void validateBacktestRun(BacktestRun backtestRun) {
        if (backtestRun == null) {
            throw new IllegalArgumentException("Backtest run must not be null");
        }

        if (backtestRun.getUserId() == null) {
            throw new IllegalArgumentException("Backtest userId must not be null");
        }

        if (backtestRun.getSymbol() == null || backtestRun.getSymbol().isBlank()) {
            throw new IllegalArgumentException("Backtest symbol must not be blank");
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