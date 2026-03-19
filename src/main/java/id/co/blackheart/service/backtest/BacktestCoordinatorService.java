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
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestCoordinatorService {

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
        Users user = usersRepository.findByUserId(backtestRun.getUserId());

        List<MarketData> candles15m = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getSymbol(),
                "15m",
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<MarketData> candles4h = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getSymbol(),
                backtestRun.getInterval(),
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<FeatureStore> featureStores4h = featureStoreRepository.findBySymbolIntervalAndRange(
                backtestRun.getSymbol(),
                backtestRun.getInterval(),
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        if (candles15m == null || candles15m.isEmpty()) {
            throw new IllegalArgumentException("No 15m market data found for backtest");
        }

        if (candles4h == null || candles4h.isEmpty()) {
            throw new IllegalArgumentException("No 4h market data found for backtest");
        }

        Map<LocalDateTime, MarketData> candle4hByEndTime = candles4h.stream()
                .collect(Collectors.toMap(MarketData::getEndTime, Function.identity(), (a, b) -> a));

        Map<LocalDateTime, FeatureStore> feature4hByStartTime = featureStores4h.stream()
                .collect(Collectors.toMap(FeatureStore::getStartTime, Function.identity(), (a, b) -> a));

        BacktestState state = BacktestState.initial(backtestRun);

        for (MarketData candle15m : candles15m) {
            // 1) listener reacts on every 15m candle
            if (state.getActiveTrade() != null) {
                PositionSnapshot positionSnapshot = backtestPositionSnapshotMapper.toSnapshot(state.getActiveTrade());

                ListenerContext listenerContext = ListenerContext.builder()
                        .asset(backtestRun.getSymbol())
                        .interval("15m")
                        .positionSnapshot(positionSnapshot)
                        .monitorCandle(candle15m)
                        .build();

                ListenerDecision listenerDecision = tradeListenerService.evaluate(listenerContext);

                if (listenerDecision.isTriggered()) {
                    StrategyContext listenerCloseContext = StrategyContext.builder()
                            .user(user)
                            .asset(backtestRun.getSymbol())
                            .interval("15m")
                            .marketData(candle15m)
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
                }
            }

            // 2) if this 15m candle is also a 4h boundary, strategy reacts
            MarketData candle4h = candle4hByEndTime.get(candle15m.getEndTime());
            if (candle4h != null) {
                FeatureStore featureStore4h = feature4hByStartTime.get(candle4h.getStartTime());

                if (featureStore4h == null) {
                    log.warn("FeatureStore missing for symbol={} interval={} startTime={}",
                            backtestRun.getSymbol(), backtestRun.getInterval(), candle4h.getStartTime());
                } else {
                    PositionSnapshot positionSnapshot = backtestPositionSnapshotMapper.toSnapshot(state.getActiveTrade());

                    StrategyContext strategyContext = StrategyContext.builder()
                            .user(user)
                            .asset(backtestRun.getSymbol())
                            .interval(backtestRun.getInterval())
                            .marketData(candle4h)
                            .featureStore(featureStore4h)
                            .positionSnapshot(positionSnapshot)
                            .build();

                    StrategyDecision decision = trendFollowingStrategyService.execute(strategyContext);

                    if (!DecisionType.HOLD.equals(decision.getDecisionType())) {
                        log.info("Backtest strategy decision | runId={} time={} decisionType={} reason={}",
                                backtestRun.getBacktestRunId(),
                                candle4h.getEndTime(),
                                decision.getDecisionType(),
                                decision.getReason());
                    }

                    backtestTradeExecutorService.execute(backtestRun, state, strategyContext, decision);
                }
            }

            // 3) update equity/drawdown on every 15m step
            backtestStateService.updateEquityAndDrawdown(state, candle15m.getClosePrice());
        }

        return backtestMetricsService.buildSummary(backtestRun, state);
    }
}