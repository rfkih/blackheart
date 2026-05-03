package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.BaseStrategyContext;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.service.strategy.StrategyContextEnrichmentService;
import id.co.blackheart.service.strategy.StrategyExecutor;
import id.co.blackheart.service.strategy.StrategyExecutorFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingCoordinatorService {

    private static final List<String> ACTIVE_PARENT_STATUSES = List.of("OPEN", "PARTIALLY_CLOSED");

    private final TradesRepository tradesRepository;
    private final TradePositionRepository tradePositionRepository;
    private final PortfolioRepository portfolioRepository;
    private final PortfolioService portfolioService;
    private final StrategyExecutorFactory strategyExecutorFactory;
    private final StrategyContextEnrichmentService strategyContextEnrichmentService;
    private final LiveTradingDecisionExecutorService liveTradingDecisionExecutorService;
    private final LivePositionSnapshotMapper livePositionSnapshotMapper;

    public void process(
            Account account,
            AccountStrategy accountStrategy,
            String asset,
            String interval,
            MarketData marketData,
            FeatureStore featureStore
    ) {
        if (!isApplicable(accountStrategy, interval)) {
            return;
        }

        portfolioService.refreshAccountBalance(account);

        String strategyCode = accountStrategy.getStrategyCode();

        try {
            // V40 — definition-scope kill-switch. When the strategy_definition
            // is disabled, skip resolution entirely so no decision is evaluated
            // and no order/paper-trade row can be written for ANY account.
            java.util.Optional<StrategyExecutor> executorOpt =
                    strategyExecutorFactory.getIfDefinitionEnabled(strategyCode);
            if (executorOpt.isEmpty()) {
                return;
            }
            StrategyExecutor executor = executorOpt.get();
            StrategyRequirements requirements = executor.getRequirements();

            LiveState liveState = loadLiveState(account, accountStrategy, asset, interval);

            BaseStrategyContext baseContext = buildBaseContext(
                    account,
                    accountStrategy,
                    asset,
                    interval,
                    marketData,
                    featureStore,
                    liveState
            );

            EnrichedStrategyContext context =
                    strategyContextEnrichmentService.enrich(baseContext, requirements);

            StrategyDecision decision = executor.execute(context);

            log.info(
                    "Strategy decision |  interval={} decision={} reason={} score={} ",
                    interval,
                    decision.getDecisionType(),
                    decision.getReason(),
                    decision.getConfidenceScore()
            );

            if (decision.isNoAction()) {
                return;
            }

            liveTradingDecisionExecutorService.execute(
                    liveState.activeTrade(),
                    context,
                    decision
            );

        } catch (Exception e) {
            log.error(
                    "Live trading coordinator failed | accountId={} strategy={} asset={} interval={}",
                    account.getAccountId(),
                    strategyCode,
                    asset,
                    interval,
                    e
            );
        }
    }

    private boolean isApplicable(AccountStrategy accountStrategy, String interval) {
        return accountStrategy != null
                && accountStrategy.getStrategyCode() != null
                && interval != null
                && interval.equalsIgnoreCase(accountStrategy.getIntervalName());
    }

    private BaseStrategyContext buildBaseContext(
            Account account,
            AccountStrategy accountStrategy,
            String asset,
            String interval,
            MarketData marketData,
            FeatureStore featureStore,
            LiveState liveState
    ) {
        return BaseStrategyContext.builder()
                .account(account)
                .accountStrategy(accountStrategy)
                .asset(asset)
                .interval(interval)
                .marketData(marketData)
                .featureStore(featureStore)
                .positionSnapshot(liveState.positionSnapshot())
                .hasOpenPosition(liveState.positionSnapshot() != null && liveState.positionSnapshot().isHasOpenPosition())
                .openPositionCount(liveState.openPositionCount())
                .executionMetadata(buildExecutionMetadata(liveState))
                .cashBalance(liveState.cashBalance())
                .assetBalance(liveState.assetBalance())
                .riskPerTradePct(account.getRiskAmount())
                .allowLong(Boolean.TRUE.equals(accountStrategy.getAllowLong()))
                .allowShort(Boolean.TRUE.equals(accountStrategy.getAllowShort()))
                .maxOpenPositions(accountStrategy.getMaxOpenPositions())
                .currentOpenTradeCount(liveState.currentOpenTradeCount())
                .diagnostics(Map.of(
                        "source", "live",
                        "strategyCode", accountStrategy.getStrategyCode()
                ))
                .build();
    }

    private LiveState loadLiveState(
            Account account,
            AccountStrategy accountStrategy,
            String asset,
            String interval
    ) {
        AssetPair assetPair = resolveAssetPair(asset);


        List<Portfolio> portfolios = portfolioRepository.findAllByAccountIdAndAssetIn(
                account.getAccountId(),
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
                account.getAccountId(),
                accountStrategy.getAccountStrategyId(),
                asset,
                interval,
                ACTIVE_PARENT_STATUSES
        );

        Trades activeTrade = activeTrades.isEmpty() ? null : activeTrades.getFirst();

        List<TradePosition> activeTradePositions =
                activeTrade == null
                        ? List.of()
                        : tradePositionRepository.findAllByTradeIdAndStatus(activeTrade.getTradeId(), "OPEN");

        PositionSnapshot positionSnapshot = buildPositionSnapshot(activeTradePositions);

        return new LiveState(
                cashBalance,
                assetBalance,
                activeTrade,
                activeTrades.size(),
                activeTradePositions.size(),
                positionSnapshot
        );
    }

    private Map<String, Object> buildExecutionMetadata(LiveState liveState) {
        UUID activeTradeId = liveState.activeTrade() != null ? liveState.activeTrade().getTradeId() : null;

        return Map.of(
                "source", "live",
                "activeTradeId", activeTradeId == null ? "" : activeTradeId.toString(),
                "currentOpenTradeCount", liveState.currentOpenTradeCount(),
                "openPositionCount", liveState.openPositionCount()
        );
    }

    private PositionSnapshot buildPositionSnapshot(List<TradePosition> activeTradePositions) {
        if (activeTradePositions != null && !activeTradePositions.isEmpty()) {
            return livePositionSnapshotMapper.toSnapshot(activeTradePositions.getFirst());
        }

        return PositionSnapshot.builder()
                .hasOpenPosition(false)
                .build();
    }

    private static final List<String> KNOWN_QUOTE_ASSETS = List.of(
            "USDT", "USDC", "BUSD", "TUSD", "FDUSD", "BTC", "ETH", "BNB"
    );

    private AssetPair resolveAssetPair(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be blank");
        }

        for (String quote : KNOWN_QUOTE_ASSETS) {
            if (symbol.endsWith(quote) && symbol.length() > quote.length()) {
                return new AssetPair(
                        symbol.substring(0, symbol.length() - quote.length()),
                        quote
                );
            }
        }

        throw new IllegalArgumentException("Unsupported symbol format: " + symbol);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record AssetPair(String baseAsset, String quoteAsset) {
    }

    private record LiveState(
            BigDecimal cashBalance,
            BigDecimal assetBalance,
            Trades activeTrade,
            Integer currentOpenTradeCount,
            Integer openPositionCount,
            PositionSnapshot positionSnapshot
    ) {
    }
}