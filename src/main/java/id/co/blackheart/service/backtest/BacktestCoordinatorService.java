package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.repository.UsersRepository;
import id.co.blackheart.service.strategy.TrendFollowingStrategyService;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestCoordinatorService {

    private final UsersRepository usersRepository;
    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final TrendFollowingStrategyService trendFollowingStrategyService;
    private final BacktestTradeExecutorService backtestTradeExecutorService;
    private final BacktestMetricsService backtestMetricsService;
    private final BacktestPositionSnapshotMapper backtestPositionSnapshotMapper;

    public BacktestExecutionSummary execute(BacktestRun backtestRun) {
        Users user = usersRepository.findByUserId(backtestRun.getUserId());

        List<MarketData> candles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getSymbol(),
                backtestRun.getInterval(),
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("No historical candles found for backtest");
        }

        BacktestState state = BacktestState.initial(backtestRun);

        for (MarketData candle : candles) {
            FeatureStore featureStore = featureStoreRepository.getFeatureForBacktest(
                    backtestRun.getSymbol(),
                    backtestRun.getInterval(),
                    candle.getStartTime()
            ).orElse(null);

            if (featureStore == null) {
                log.warn("FeatureStore missing for symbol={} interval={} candleStart={}",
                        backtestRun.getSymbol(), backtestRun.getInterval(), candle.getStartTime());
                continue;
            }

            PositionSnapshot positionSnapshot =
                    backtestPositionSnapshotMapper.toSnapshot(state.getActiveTrade());

            StrategyContext context = StrategyContext.builder()
                    .user(user)
                    .asset(backtestRun.getSymbol())
                    .interval(backtestRun.getInterval())
                    .marketData(candle)
                    .featureStore(featureStore)
                    .positionSnapshot(positionSnapshot)
                    .build();

            StrategyDecision decision = trendFollowingStrategyService.execute(context);

            if (!DecisionType.HOLD.equals(decision.getDecisionType())) {
                log.info("Backtest decision | runId={} time={} decisionType={} reason={}",
                        backtestRun.getBacktestRunId(),
                        candle.getEndTime(),
                        decision.getDecisionType(),
                        decision.getReason());
            }

            backtestTradeExecutorService.execute(backtestRun, state, context, decision);
        }

        return backtestMetricsService.buildSummary(backtestRun, state);
    }
}