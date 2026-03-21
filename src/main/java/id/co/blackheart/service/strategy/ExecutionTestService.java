package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionTestService implements StrategyExecutor {

    public static final String STRATEGY_NAME = "EXECUTION_TEST";
    public static final String SIDE_LONG = "LONG";
    public static final String SIDE_SHORT = "SHORT";

    @SuppressWarnings("unused")
    public StrategyDecision execute(StrategyContext context) {
        if (context == null || context.getMarketData() == null) {
            return hold("Invalid context");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();

        BigDecimal closePrice = marketData.getClosePrice();

        log.info("active trade in test  {}", context.getActiveTrade());

        if (context.getActiveTrade() != null){
            return hold( "Active trades exist");
        }

        if (closePrice == null) {
            return hold("Close price is null");
        }

        BigDecimal atr = BigDecimal.ONE;
        if (featureStore != null
                && featureStore.getAtr() != null
                && featureStore.getAtr().compareTo(BigDecimal.ZERO) > 0) {
            atr = featureStore.getAtr();
        }

        if (context.isAllowLong()) {
            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_LONG)
                    .strategyName(STRATEGY_NAME)
                    .strategyInterval(context.getInterval())
                    .side(SIDE_LONG)
                    .reason("Execution test long")
                    .positionSize(BigDecimal.ONE)
                    .stopLossPrice(closePrice.subtract(atr.multiply(new BigDecimal("1.5"))))
                    .takeProfitPrice(closePrice.add(atr.multiply(new BigDecimal("2.0"))))
                    .entryAtr(atr)
                    .build();
        }

        if (context.isAllowShort()) {
            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_SHORT)
                    .strategyName(STRATEGY_NAME)
                    .strategyInterval(context.getInterval())
                    .side(SIDE_SHORT)
                    .reason("Execution test short")
                    .positionSize(BigDecimal.ONE)
                    .stopLossPrice(closePrice.add(atr.multiply(new BigDecimal("0.5"))))
                    .takeProfitPrice(closePrice.subtract(atr.multiply(new BigDecimal("1.0"))))
                    .entryAtr(atr)
                    .build();
        }

        return hold("Both long and short disabled");
    }

    private StrategyDecision hold(String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }
}