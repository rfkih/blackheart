package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Trades;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionTestService implements StrategyExecutor {

    public static final String STRATEGY_NAME = "TEST";
    public static final String SIDE_LONG = "LONG";
    public static final String SIDE_SHORT = "SHORT";

    private static final String EXIT_TEST_MANUAL_CLOSE = "TEST_MANUAL_CLOSE";

    private static final BigDecimal DEFAULT_ATR = BigDecimal.ONE;
    private static final BigDecimal LONG_STOP_ATR = new BigDecimal("1.5");
    private static final BigDecimal LONG_TP_ATR = new BigDecimal("2.0");
    private static final BigDecimal SHORT_STOP_ATR = new BigDecimal("1.5");
    private static final BigDecimal SHORT_TP_ATR = new BigDecimal("2.0");

    @Override
    public StrategyDecision execute(StrategyContext context) {
        if (context == null || context.getMarketData() == null) {
            return hold(context, "Invalid context");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        Trades activeTrade = context.getActiveTrade();

        BigDecimal closePrice = marketData.getClosePrice();
        if (closePrice == null) {
            return hold(context, "Close price is null");
        }

        BigDecimal atr = resolveAtr(featureStore);

        log.info(
                "Execution test | interval={} allowLong={} allowShort={} activeTradeId={} activeSide={} closePrice={}",
                context.getInterval(),
                context.isAllowLong(),
                context.isAllowShort(),
                activeTrade != null ? activeTrade.getTradeId() : null,
                activeTrade != null ? activeTrade.getSide() : null,
                closePrice
        );

        if (activeTrade == null) {
            return handleNoActiveTrade(context, closePrice, atr, featureStore);
        }

        return handleActiveTrade(context, activeTrade);
    }

    private StrategyDecision handleNoActiveTrade(
            StrategyContext context,
            BigDecimal closePrice,
            BigDecimal atr,
            FeatureStore featureStore
    ) {
        if (context.isAllowLong()) {
            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_LONG)
                    .strategyName(STRATEGY_NAME)
                    .strategyInterval(context.getInterval())
                    .side(SIDE_LONG)
                    .reason("Execution test open long")
                    .positionSize(BigDecimal.ONE)
                    .stopLossPrice(closePrice.subtract(atr.multiply(LONG_STOP_ATR)))
                    .takeProfitPrice(closePrice.add(atr.multiply(LONG_TP_ATR)))
                    .entryAtr(atr)
                    .entryAdx(featureStore != null ? featureStore.getAdx() : null)
                    .entryRsi(featureStore != null ? featureStore.getRsi() : null)
                    .entryTrendRegime(featureStore != null ? featureStore.getTrendRegime() : null)
                    .build();
        }

        if (context.isAllowShort()) {
            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_SHORT)
                    .strategyName(STRATEGY_NAME)
                    .strategyInterval(context.getInterval())
                    .side(SIDE_SHORT)
                    .reason("Execution test open short")
                    .positionSize(BigDecimal.ONE)
                    .stopLossPrice(closePrice.add(atr.multiply(SHORT_STOP_ATR)))
                    .takeProfitPrice(closePrice.subtract(atr.multiply(SHORT_TP_ATR)))
                    .entryAtr(atr)
                    .entryAdx(featureStore != null ? featureStore.getAdx() : null)
                    .entryRsi(featureStore != null ? featureStore.getRsi() : null)
                    .entryTrendRegime(featureStore != null ? featureStore.getTrendRegime() : null)
                    .build();
        }

        return hold(context, "Both long and short disabled");
    }

    private StrategyDecision handleActiveTrade(
            StrategyContext context,
            Trades activeTrade
    ) {
        String side = activeTrade.getSide();

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            if (!context.isAllowLong()) {
                return StrategyDecision.builder()
                        .decisionType(DecisionType.CLOSE_LONG)
                        .strategyName(STRATEGY_NAME)
                        .strategyInterval(context.getInterval())
                        .side(SIDE_LONG)
                        .exitReason(EXIT_TEST_MANUAL_CLOSE)
                        .reason("Execution test close long")
                        .build();
            }

            return hold(context, "Long active, keep holding");
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            if (!context.isAllowShort()) {
                return StrategyDecision.builder()
                        .decisionType(DecisionType.CLOSE_SHORT)
                        .strategyName(STRATEGY_NAME)
                        .strategyInterval(context.getInterval())
                        .side(SIDE_SHORT)
                        .exitReason(EXIT_TEST_MANUAL_CLOSE)
                        .reason("Execution test close short")
                        .build();
            }

            return hold(context, "Short active, keep holding");
        }

        return hold(context, "Unknown active trade side");
    }

    private BigDecimal resolveAtr(FeatureStore featureStore) {
        if (featureStore != null
                && featureStore.getAtr() != null
                && featureStore.getAtr().compareTo(BigDecimal.ZERO) > 0) {
            return featureStore.getAtr();
        }
        return DEFAULT_ATR;
    }

    private StrategyDecision hold(StrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context != null ? context.getInterval() : null)
                .reason(reason)
                .build();
    }
}