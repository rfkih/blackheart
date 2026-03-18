package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.service.strategy.TrendFollowingStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static id.co.blackheart.util.TradeConstant.DecisionType.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingCoordinatorService {

    private final TradesRepository tradesRepository;
    private final PortfolioRepository portfolioRepository;
    private final PortfolioService portfolioService;
    private final TrendFollowingStrategyService trendFollowingStrategyService;
    private final LiveTradingDecisionExecutorService liveTradingDecisionExecutorService;

    public void process(Users user, String asset, String interval, MarketData marketData, FeatureStore featureStore) {
        try {
            Optional<Trades> activeTradeOpt = tradesRepository.findLatestOpenTrade(
                    user.getUserId(),
                    asset,
                    TrendFollowingStrategyService.STRATEGY_NAME,
                    interval
            );

            Portfolio portfolio = null;
            try {
                portfolio = portfolioRepository.findByUserIdAndAsset(user.getUserId(), "USDT").orElse(null);
            } catch (Exception e) {
                log.warn("Portfolio lookup failed | userId={} asset={}", user.getUserId(), asset, e);
            }

            StrategyContext context = StrategyContext.builder()
                    .user(user)
                    .asset(asset)
                    .interval(interval)
                    .marketData(marketData)
                    .featureStore(featureStore)
                    .activeTrade(activeTradeOpt.orElse(null))
                    .portfolio(portfolio)
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

            liveTradingDecisionExecutorService.execute(context, decision);

        } catch (Exception e) {
            log.error("Live trading coordinator failed | userId={} asset={} interval={}",
                    user.getUserId(), asset, interval, e);
        }
    }
}