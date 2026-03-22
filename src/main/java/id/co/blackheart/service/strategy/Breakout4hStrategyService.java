//package id.co.blackheart.service.strategy;
//import id.co.blackheart.dto.strategy.PositionSnapshot;
//import id.co.blackheart.dto.strategy.StrategyContext;
//import id.co.blackheart.dto.strategy.StrategyDecision;
//import id.co.blackheart.model.FeatureStore;
//import id.co.blackheart.util.TradeConstant.*;
//import lombok.RequiredArgsConstructor;
//import org.springframework.stereotype.Service;
//
//import java.math.BigDecimal;
//@Service
//@RequiredArgsConstructor
//public class Breakout4hStrategyService implements StrategyExecutor {
//
//    @Override
//    public StrategyDecision execute(StrategyContext context) {
//        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
//            return hold("Invalid context");
//        }
//
//        FeatureStore f = context.getFeatureStore();
//        BigDecimal close = context.getMarketData().getClosePrice();
//        PositionSnapshot p = context.getPositionSnapshot();
//
//        if (p != null && p.isHasOpenPosition()) {
//            return hold("Open trade managed elsewhere");
//        }
//
//        boolean bullishRegime =
//                "BULL".equalsIgnoreCase(f.getTrendRegime())
//                        && f.getAdx() != null
//                        && f.getAdx().compareTo(new BigDecimal("22")) >= 0;
//
//        boolean breakout = Boolean.TRUE.equals(f.getIsBullishBreakout());
//
//        if (!bullishRegime || !breakout || f.getAtr() == null || f.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
//            return hold("No valid 4h breakout entry");
//        }
//
//        BigDecimal atr = f.getAtr();
//
//        return StrategyDecision.builder()
//                .decisionType(DecisionType.OPEN_LONG)
//                .strategyName("BREAKOUT_4H")
//                .strategyInterval("4h")
//                .side("LONG")
//                .reason("4h breakout long")
//                .positionSize(BigDecimal.ONE)
//                .stopLossPrice(close.subtract(atr.multiply(new BigDecimal("1.8"))))
//                .takeProfitPrice(close.add(atr.multiply(new BigDecimal("3.5"))))
//                .build();
//    }
//
//    private StrategyDecision hold(String reason) {
//        return StrategyDecision.builder()
//                .decisionType(DecisionType.HOLD)
//                .strategyName("BREAKOUT_4H")
//                .reason(reason)
//                .build();
//    }
//}
