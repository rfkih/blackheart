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
//public class Pullback15mWith4hBiasStrategyService implements StrategyExecutor {
//
//    @Override
//    public StrategyDecision execute(StrategyContext context) {
//        if (context == null
//                || context.getMarketData() == null
//                || context.getFeatureStore() == null
//                || context.getBiasMarketData() == null
//                || context.getBiasFeatureStore() == null) {
//            return hold("Invalid context");
//        }
//
//        FeatureStore entry = context.getFeatureStore();       // 15m
//        FeatureStore bias = context.getBiasFeatureStore();    // 4h
//        BigDecimal close = context.getMarketData().getClosePrice();
//        BigDecimal biasClose = context.getBiasMarketData().getClosePrice();
//        PositionSnapshot p = context.getPositionSnapshot();
//
//        if (p != null && p.isHasOpenPosition()) {
//            return hold("15m open trade managed elsewhere");
//        }
//
//        boolean bullishBias =
//                "BULL".equalsIgnoreCase(bias.getTrendRegime())
//                        && bias.getEma50() != null
//                        && bias.getEma200() != null
//                        && biasClose != null
//                        && biasClose.compareTo(bias.getEma50()) > 0
//                        && bias.getEma50().compareTo(bias.getEma200()) > 0
//                        && bias.getAdx() != null
//                        && bias.getAdx().compareTo(new BigDecimal("25")) >= 0
//                        && bias.getEfficiencyRatio20() != null
//                        && bias.getEfficiencyRatio20().compareTo(new BigDecimal("0.40")) >= 0;
//
//        boolean pullback = Boolean.TRUE.equals(entry.getIsBullishPullback());
//        boolean momentum =
//                entry.getPlusDI() != null
//                        && entry.getMinusDI() != null
//                        && entry.getPlusDI().compareTo(entry.getMinusDI()) > 0
//                        && entry.getMacdHistogram() != null
//                        && entry.getMacdHistogram().compareTo(BigDecimal.ZERO) > 0;
//
//        boolean reclaim =
//                entry.getEma20() != null
//                        && entry.getEma50() != null
//                        && close != null
//                        && close.compareTo(entry.getEma20()) >= 0
//                        && close.compareTo(entry.getEma50()) >= 0;
//
//        boolean volumeOk =
//                entry.getRelativeVolume20() == null
//                        || entry.getRelativeVolume20().compareTo(new BigDecimal("1.05")) >= 0;
//
//        boolean notOverextended =
//                entry.getAtr() == null
//                        || entry.getEma20() == null
//                        || close == null
//                        || close.compareTo(entry.getEma20().add(entry.getAtr().multiply(new BigDecimal("0.80")))) <= 0;
//
//        if (!bullishBias || !pullback || !momentum || !reclaim || !volumeOk || !notOverextended
//                || entry.getAtr() == null || entry.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
//            return hold("No valid 15m pullback continuation entry");
//        }
//
//        BigDecimal atr = entry.getAtr();
//
//        return StrategyDecision.builder()
//                .decisionType(DecisionType.OPEN_LONG)
//                .strategyName("PULLBACK_15M_WITH_4H_BIAS")
//                .strategyInterval("15m")
//                .side("LONG")
//                .reason("4h bullish bias + 15m pullback continuation")
//                .positionSize(BigDecimal.ONE)
//                .stopLossPrice(close.subtract(atr.multiply(new BigDecimal("1.2"))))
//                .takeProfitPrice(close.add(atr.multiply(new BigDecimal("2.0"))))
//                .build();
//    }
//
//    private StrategyDecision hold(String reason) {
//        return StrategyDecision.builder()
//                .decisionType(DecisionType.HOLD)
//                .strategyName("PULLBACK_15M_WITH_4H_BIAS")
//                .reason(reason)
//                .build();
//    }
//}