package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.UserStrategy;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.strategy.StrategyExecutor;
import id.co.blackheart.service.strategy.StrategyExecutorFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

import static id.co.blackheart.util.TradeConstant.DecisionType.HOLD;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingCoordinatorService {

    private static final String SYMBOL = "BTCUSDT";
    private static final List<String> ACTIVE_PARENT_STATUSES = List.of("OPEN", "PARTIALLY_CLOSED");

    private final TradesRepository tradesRepository;
    private final TradePositionRepository tradePositionRepository;
    private final StrategyExecutorFactory strategyExecutorFactory;
    private final LiveTradingDecisionExecutorService liveTradingDecisionExecutorService;
    private final LivePositionSnapshotMapper livePositionSnapshotMapper;
    private final FeatureStoreRepository featureStoreRepository;
    private final MarketDataRepository marketDataRepository;
    private final PortfolioRepository portfolioRepository;

    public void process(
            Users user,
            UserStrategy userStrategy,
            String asset,
            String interval,
            MarketData marketData,
            FeatureStore featureStore
    ) {
        String strategyCode = userStrategy.getStrategyCode();

        if (!interval.equals(userStrategy.getIntervalName())) {
            return;
        }

        try {
            StrategyExecutor executor = strategyExecutorFactory.get(strategyCode);

            AssetPair assetPair = resolveAssetPair(asset);

            List<Portfolio> portfolios = portfolioRepository.findAllByUserIdAndAssetIn(
                    user.getUserId(),
                    List.of(assetPair.baseAsset(), assetPair.quoteAsset())
            );

            BigDecimal cashBalance = BigDecimal.ZERO;
            BigDecimal assetBalance = BigDecimal.ZERO;

            for (Portfolio portfolio : portfolios) {
                if (portfolio == null || portfolio.getAsset() == null) {
                    continue;
                }

                if (assetPair.quoteAsset().equalsIgnoreCase(portfolio.getAsset())) {
                    cashBalance = safe(portfolio.getBalance());
                } else if (assetPair.baseAsset().equalsIgnoreCase(portfolio.getAsset())) {
                    assetBalance = safe(portfolio.getBalance());
                }
            }

            List<Trades> activeTrades = tradesRepository.findAllActiveTrades(
                    user.getUserId(),
                    userStrategy.getUserStrategyId(),
                    asset,
                    interval,
                    ACTIVE_PARENT_STATUSES
            );

            Trades activeTrade = activeTrades.isEmpty() ? null : activeTrades.getFirst();

            List<TradePosition> activeTradePositions =
                    activeTrade == null
                            ? List.of()
                            : tradePositionRepository.findAllByTradeIdAndStatus(activeTrade.getTradeId(), "OPEN");

            PositionSnapshot positionSnapshot = activeTradePositions.isEmpty()
                    ? livePositionSnapshotMapper.toSnapshot(activeTrade)
                    : livePositionSnapshotMapper.toSnapshot(activeTradePositions.getFirst());

            FeatureStore biasFeatureStore = null;
            MarketData biasMarketData = null;

            if ("PULLBACK_15M_WITH_4H_BIAS".equals(strategyCode) && "15m".equalsIgnoreCase(interval)) {
                biasFeatureStore = featureStoreRepository.findLatestBySymbolAndInterval(SYMBOL, "4h").orElse(null);
                biasMarketData = marketDataRepository.findLatestBySymbolAndInterval(SYMBOL, "4h").orElse(null);
            }

            StrategyContext context = StrategyContext.builder()
                    .user(user)
                    .asset(asset)
                    .interval(interval)
                    .marketData(marketData)
                    .featureStore(featureStore)
                    .activeTrade(activeTrade)
                    .activeTrades(activeTrades)
                    .activeTradePositions(activeTradePositions)
                    .positionSnapshot(positionSnapshot)
                    .cashBalance(cashBalance)
                    .assetBalance(assetBalance)
                    .riskPerTradePct(user.getRiskAmount())
                    .userStrategyId(userStrategy.getUserStrategyId())
                    .strategyCode(userStrategy.getStrategyCode())
                    .biasFeatureStore(biasFeatureStore)
                    .biasMarketData(biasMarketData)
                    .allowLong(Boolean.TRUE.equals(userStrategy.getAllowLong()))
                    .allowShort(Boolean.TRUE.equals(userStrategy.getAllowShort()))
                    .maxOpenPositions(userStrategy.getMaxOpenPositions())
                    .currentOpenTradeCount(activeTrades.size())
                    .build();

            StrategyDecision decision = executor.execute(context);

            log.info(
                    "Strategy decision | userId={} strategy={} asset={} interval={} decisionType={} reason={} cashBalance={} assetBalance={}",
                    user.getUserId(),
                    strategyCode,
                    asset,
                    interval,
                    decision.getDecisionType(),
                    decision.getReason(),
                    cashBalance,
                    assetBalance
            );

            if (HOLD.equals(decision.getDecisionType())) {
                return;
            }

            liveTradingDecisionExecutorService.execute(activeTrade, context, decision);

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

    private AssetPair resolveAssetPair(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be blank");
        }

        if (symbol.endsWith("USDT")) {
            return new AssetPair(
                    symbol.substring(0, symbol.length() - 4),
                    "USDT"
            );
        }

        throw new IllegalArgumentException("Unsupported symbol format: " + symbol);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record AssetPair(String baseAsset, String quoteAsset) {
    }
}