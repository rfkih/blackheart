package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.*;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.strategy.StrategyExecutor;
import id.co.blackheart.service.strategy.StrategyExecutorFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static id.co.blackheart.util.TradeConstant.DecisionType.HOLD;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingCoordinatorService {

    private static final String SYMBOL = "BTCUSDT";

    private final TradesRepository tradesRepository;
    private final StrategyExecutorFactory strategyExecutorFactory;
    private final LiveTradingDecisionExecutorService liveTradingDecisionExecutorService;
    private final LivePositionSnapshotMapper livePositionSnapshotMapper;
    private final FeatureStoreRepository featureStoreRepository;
    private final MarketDataRepository marketDataRepository;

    public void process(
            Users user,
            UserStrategy userStrategy,
            String asset,
            String interval,
            MarketData marketData,
            FeatureStore featureStore
    ) {
        String strategyCode = userStrategy.getStrategyCode();

        if (!interval.equals(userStrategy.getIntervalName())){
            return;
        }
        log.info("Start Real Trading at interval {}", userStrategy.getIntervalName());

        try {
            StrategyExecutor executor = strategyExecutorFactory.get(strategyCode);

            log.info("strategy Code");

            Optional<Trades> activeTradeOpt = tradesRepository.findLatestOpenTrade(
                    user.getUserId(),
                    asset,
                    strategyCode,
                    interval
            );

            Trades activeTrade = activeTradeOpt.orElse(null);
            PositionSnapshot positionSnapshot = livePositionSnapshotMapper.toSnapshot(activeTrade);

            FeatureStore biasFeatureStore = null;
            MarketData biasMarketData = null;

            if ("PULLBACK_15M_WITH_4H_BIAS".equals(strategyCode) && "15m".equalsIgnoreCase(interval)) {
                biasFeatureStore = featureStoreRepository.findLatestBySymbolAndInterval(
                        SYMBOL,
                        "4h"
                ).orElse(null);

                biasMarketData = marketDataRepository.findLatestBySymbolAndInterval(
                        SYMBOL,
                        "4h"
                ).orElse(null);
            }

            StrategyContext context = StrategyContext.builder()
                    .user(user)
                    .asset(asset)
                    .interval(interval)
                    .marketData(marketData)
                    .activeTrade(activeTrade)
                    .userStrategyId(userStrategy.getUserStrategyId())
                    .featureStore(featureStore)
                    .positionSnapshot(positionSnapshot)
                    .biasFeatureStore(biasFeatureStore)
                    .biasMarketData(biasMarketData)
                    .allowLong(userStrategy.getAllowLong())
                    .allowShort(userStrategy.getAllowShort())
                    .build();

            StrategyDecision decision = executor.execute(context);

            log.info(
                    "Strategy decision | userId={} strategy={} asset={} interval={} decisionType={} reason={}",
                    user.getUserId(),
                    strategyCode,
                    asset,
                    interval,
                    decision.getDecisionType(),
                    decision.getReason()
            );

            if (HOLD.equals(decision.getDecisionType())) {
                return;
            }

            liveTradingDecisionExecutorService.execute(
                    activeTrade,
                    context,
                    decision
            );

        } catch (Exception e) {
            log.error(
                    "Live trading coordinator failed | userId={} strategy={} asset={} interval={}",
                    user.getUserId(),
                    strategyCode,
                    asset,
                    interval,
                    e
            );
        }
    }
}