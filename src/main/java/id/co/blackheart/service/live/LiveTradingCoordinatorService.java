package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.strategy.TrendFollowingStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static id.co.blackheart.util.TradeConstant.DecisionType.HOLD;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingCoordinatorService {

    private final TradesRepository tradesRepository;
    private final TrendFollowingStrategyService trendFollowingStrategyService;
    private final LiveTradingDecisionExecutorService liveTradingDecisionExecutorService;
    private final LivePositionSnapshotMapper livePositionSnapshotMapper;

    public void process(Users user, String asset, String interval, MarketData marketData, FeatureStore featureStore) {
        try {
            Optional<Trades> activeTradeOpt = tradesRepository.findLatestOpenTrade(
                    user.getUserId(),
                    asset,
                    TrendFollowingStrategyService.STRATEGY_NAME,
                    interval
            );

            Trades activeTrade = activeTradeOpt.orElse(null);
            PositionSnapshot positionSnapshot = livePositionSnapshotMapper.toSnapshot(activeTrade);

            StrategyContext context = StrategyContext.builder()
                    .user(user)
                    .asset(asset)
                    .interval(interval)
                    .marketData(marketData)
                    .featureStore(featureStore)
                    .positionSnapshot(positionSnapshot)
                    .build();

            StrategyDecision decision = trendFollowingStrategyService.execute(context);

            log.info("Strategy decision | userId={} asset={} interval={} decisionType={} reason={}",
                    user.getUserId(),
                    asset,
                    interval,
                    decision.getDecisionType(),
                    decision.getReason());

            if (HOLD.equals(decision.getDecisionType())) {
                return;
            }

            liveTradingDecisionExecutorService.execute(activeTrade, context, decision);

        } catch (Exception e) {
            log.error("Live trading coordinator failed | userId={} asset={} interval={}",
                    user.getUserId(), asset, interval, e);
        }
    }
}