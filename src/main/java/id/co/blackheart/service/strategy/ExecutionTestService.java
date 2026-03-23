package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.PositionSnapshot;
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

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String EXIT_STRUCTURE_TP1_TP2_RUNNER = "TP1_TP2_RUNNER";
    private static final String EXIT_STRUCTURE_RUNNER_ONLY = "RUNNER_ONLY";

    private static final String TARGET_ALL = "ALL";
    private static final String TARGET_SINGLE = "SINGLE";
    private static final String TARGET_RUNNER = "RUNNER";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal DEFAULT_ATR = BigDecimal.ONE;

    private static final BigDecimal LONG_STOP_ATR = new BigDecimal("1.5");
    private static final BigDecimal SHORT_STOP_ATR = new BigDecimal("1.5");

    private static final BigDecimal SINGLE_TP_ATR = new BigDecimal("2.0");
    private static final BigDecimal TP1_ATR = new BigDecimal("2.0");
    private static final BigDecimal TP2_ATR = new BigDecimal("3.5");

    private static final BigDecimal BREAK_EVEN_TRIGGER_ATR = new BigDecimal("1.0");
    private static final BigDecimal TRAIL_TRIGGER_ATR = new BigDecimal("2.0");
    private static final BigDecimal TRAIL_DISTANCE_ATR = new BigDecimal("1.2");
    private static final BigDecimal EXTEND_TP_ATR = new BigDecimal("1.0");

    @Override
    public StrategyDecision execute(StrategyContext context) {
        if (context == null || context.getMarketData() == null) {
            return hold(context, "Invalid context");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        Trades activeTrade = context.getActiveTrade();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();

        BigDecimal closePrice = marketData.getClosePrice();
        if (closePrice == null) {
            return hold(context, "Close price is null");
        }

        BigDecimal atr = resolveAtr(featureStore);
        String exitStructure = resolveExitStructure(context);

        log.info(
                "Execution test | interval={} exitStructure={} allowLong={} allowShort={} activeTradeId={} activeSide={} role={} closePrice={}",
                context.getInterval(),
                exitStructure,
                context.isAllowLong(),
                context.isAllowShort(),
                activeTrade != null ? activeTrade.getTradeId() : null,
                activeTrade != null ? activeTrade.getSide() : null,
                positionSnapshot != null ? positionSnapshot.getPositionRole() : null,
                closePrice
        );

        if (activeTrade == null || positionSnapshot == null || !positionSnapshot.isHasOpenPosition()) {
            return handleNoActiveTrade(context, closePrice, atr, featureStore, exitStructure);
        }

        return handleActiveTrade(context, activeTrade, positionSnapshot, closePrice, atr, exitStructure);
    }

    private StrategyDecision handleNoActiveTrade(
            StrategyContext context,
            BigDecimal closePrice,
            BigDecimal atr,
            FeatureStore featureStore,
            String exitStructure
    ) {
        if (context.isAllowLong()) {
            BigDecimal stopLoss = closePrice.subtract(atr.multiply(LONG_STOP_ATR));
            BigDecimal tp1 = closePrice.add(atr.multiply(SINGLE_TP_ATR));
            BigDecimal tp2 = closePrice.add(atr.multiply(TP2_ATR));

            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_LONG)
                    .strategyName(STRATEGY_NAME)
                    .strategyInterval(context.getInterval())
                    .side(SIDE_LONG)
                    .reason("Execution test open long with structure " + exitStructure)
                    .positionSize(BigDecimal.ONE)
                    .stopLossPrice(stopLoss)
                    .trailingStopPrice(null)
                    .takeProfitPrice1(resolveTakeProfit1(exitStructure, tp1))
                    .takeProfitPrice2(resolveTakeProfit2(exitStructure, tp2))
                    .takeProfitPrice3(null)
                    .exitStructure(exitStructure)
                    .targetPositionRole(TARGET_ALL)
                    .entryAtr(atr)
                    .entryAdx(featureStore != null ? featureStore.getAdx() : null)
                    .entryRsi(featureStore != null ? featureStore.getRsi() : null)
                    .entryTrendRegime(featureStore != null ? featureStore.getTrendRegime() : null)
                    .build();
        }

        if (context.isAllowShort()) {
            BigDecimal stopLoss = closePrice.add(atr.multiply(SHORT_STOP_ATR));
            BigDecimal tp1 = closePrice.subtract(atr.multiply(SINGLE_TP_ATR));
            BigDecimal tp2 = closePrice.subtract(atr.multiply(TP2_ATR));

            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_SHORT)
                    .strategyName(STRATEGY_NAME)
                    .strategyInterval(context.getInterval())
                    .side(SIDE_SHORT)
                    .reason("Execution test open short with structure " + exitStructure)
                    .positionSize(BigDecimal.ONE)
                    .stopLossPrice(stopLoss)
                    .trailingStopPrice(null)
                    .takeProfitPrice1(resolveTakeProfit1(exitStructure, tp1))
                    .takeProfitPrice2(resolveTakeProfit2(exitStructure, tp2))
                    .takeProfitPrice3(null)
                    .exitStructure(exitStructure)
                    .targetPositionRole(TARGET_ALL)
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
            Trades activeTrade,
            PositionSnapshot positionSnapshot,
            BigDecimal closePrice,
            BigDecimal atr,
            String exitStructure
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

            return buildLongManagementDecision(context, positionSnapshot, closePrice, atr, exitStructure);
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

            return buildShortManagementDecision(context, positionSnapshot, closePrice, atr, exitStructure);
        }

        return hold(context, "Unknown active trade side");
    }

    private StrategyDecision buildLongManagementDecision(
            StrategyContext context,
            PositionSnapshot positionSnapshot,
            BigDecimal closePrice,
            BigDecimal atr,
            String exitStructure
    ) {
        BigDecimal entryPrice = positionSnapshot.getEntryPrice();
        if (entryPrice == null || atr == null || atr.compareTo(ZERO) <= 0) {
            return hold(context, "Long management skipped due to invalid inputs");
        }

        BigDecimal move = closePrice.subtract(entryPrice);
        if (move.compareTo(atr.multiply(BREAK_EVEN_TRIGGER_ATR)) < 0) {
            return hold(context, "Long trade not ready for management update");
        }

        BigDecimal currentStop = positionSnapshot.getCurrentStopLossPrice();
        BigDecimal breakEvenStop = entryPrice;
        BigDecimal trailingStop = closePrice.subtract(atr.multiply(TRAIL_DISTANCE_ATR));

        BigDecimal updatedStop = maxNonNull(currentStop, breakEvenStop);

        if (move.compareTo(atr.multiply(TRAIL_TRIGGER_ATR)) >= 0) {
            updatedStop = maxNonNull(updatedStop, trailingStop);
        }

        BigDecimal currentTp = positionSnapshot.getTakeProfitPrice();
        BigDecimal updatedTp = currentTp;

        if (TARGET_SINGLE.equalsIgnoreCase(positionSnapshot.getPositionRole())
                || "TP1".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = closePrice.add(atr.multiply(EXTEND_TP_ATR));
        } else if ("TP2".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = closePrice.add(atr.multiply(new BigDecimal("1.5")));
        } else if ("RUNNER".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = null;
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context.getInterval())
                .side(SIDE_LONG)
                .reason("Execution test long management update")
                .stopLossPrice(updatedStop)
                .trailingStopPrice(updatedStop)
                .takeProfitPrice1(resolveManagementTp1(positionSnapshot, updatedTp))
                .takeProfitPrice2(resolveManagementTp2(positionSnapshot, updatedTp))
                .takeProfitPrice3(null)
                .targetPositionRole(resolveTargetRole(positionSnapshot, exitStructure))
                .build();
    }

    private StrategyDecision buildShortManagementDecision(
            StrategyContext context,
            PositionSnapshot positionSnapshot,
            BigDecimal closePrice,
            BigDecimal atr,
            String exitStructure
    ) {
        BigDecimal entryPrice = positionSnapshot.getEntryPrice();
        if (entryPrice == null || atr == null || atr.compareTo(ZERO) <= 0) {
            return hold(context, "Short management skipped due to invalid inputs");
        }

        BigDecimal move = entryPrice.subtract(closePrice);
        if (move.compareTo(atr.multiply(BREAK_EVEN_TRIGGER_ATR)) < 0) {
            return hold(context, "Short trade not ready for management update");
        }

        BigDecimal currentStop = positionSnapshot.getCurrentStopLossPrice();
        BigDecimal breakEvenStop = entryPrice;
        BigDecimal trailingStop = closePrice.add(atr.multiply(TRAIL_DISTANCE_ATR));

        BigDecimal updatedStop = minNonNull(currentStop, breakEvenStop);

        if (move.compareTo(atr.multiply(TRAIL_TRIGGER_ATR)) >= 0) {
            updatedStop = minNonNull(updatedStop, trailingStop);
        }

        BigDecimal currentTp = positionSnapshot.getTakeProfitPrice();
        BigDecimal updatedTp = currentTp;

        if (TARGET_SINGLE.equalsIgnoreCase(positionSnapshot.getPositionRole())
                || "TP1".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = closePrice.subtract(atr.multiply(EXTEND_TP_ATR));
        } else if ("TP2".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = closePrice.subtract(atr.multiply(new BigDecimal("1.5")));
        } else if ("RUNNER".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = null;
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context.getInterval())
                .side(SIDE_SHORT)
                .reason("Execution test short management update")
                .stopLossPrice(updatedStop)
                .trailingStopPrice(updatedStop)
                .takeProfitPrice1(resolveManagementTp1(positionSnapshot, updatedTp))
                .takeProfitPrice2(resolveManagementTp2(positionSnapshot, updatedTp))
                .takeProfitPrice3(null)
                .targetPositionRole(resolveTargetRole(positionSnapshot, exitStructure))
                .build();
    }

    private String resolveExitStructure(StrategyContext context) {
        String interval = context.getInterval();
        if (interval == null) {
            return EXIT_STRUCTURE_SINGLE;
        }

        return switch (interval.toLowerCase()) {
            case "15m" -> EXIT_STRUCTURE_TP1_RUNNER;
            case "1h" -> EXIT_STRUCTURE_SINGLE;
            case "4h" -> EXIT_STRUCTURE_TP1_TP2_RUNNER;
            case "1d" -> EXIT_STRUCTURE_RUNNER_ONLY;
            default -> EXIT_STRUCTURE_SINGLE;
        };
    }

    private BigDecimal resolveTakeProfit1(String exitStructure, BigDecimal tp1) {
        return switch (exitStructure) {
            case EXIT_STRUCTURE_SINGLE, EXIT_STRUCTURE_TP1_RUNNER, EXIT_STRUCTURE_TP1_TP2_RUNNER -> tp1;
            case EXIT_STRUCTURE_RUNNER_ONLY -> null;
            default -> tp1;
        };
    }

    private BigDecimal resolveTakeProfit2(String exitStructure, BigDecimal tp2) {
        return EXIT_STRUCTURE_TP1_TP2_RUNNER.equalsIgnoreCase(exitStructure) ? tp2 : null;
    }

    private String resolveTargetRole(PositionSnapshot snapshot, String exitStructure) {
        if (snapshot == null || snapshot.getPositionRole() == null) {
            return TARGET_ALL;
        }

        if (EXIT_STRUCTURE_SINGLE.equalsIgnoreCase(exitStructure)) {
            return TARGET_SINGLE;
        }

        if ("RUNNER".equalsIgnoreCase(snapshot.getPositionRole())) {
            return TARGET_RUNNER;
        }

        return snapshot.getPositionRole();
    }

    private BigDecimal resolveManagementTp1(PositionSnapshot snapshot, BigDecimal updatedTp) {
        if (snapshot == null || snapshot.getPositionRole() == null) {
            return updatedTp;
        }

        return switch (snapshot.getPositionRole().toUpperCase()) {
            case "SINGLE", "TP1" -> updatedTp;
            default -> null;
        };
    }

    private BigDecimal resolveManagementTp2(PositionSnapshot snapshot, BigDecimal updatedTp) {
        if (snapshot == null || snapshot.getPositionRole() == null) {
            return null;
        }

        return "TP2".equalsIgnoreCase(snapshot.getPositionRole()) ? updatedTp : null;
    }

    private BigDecimal resolveAtr(FeatureStore featureStore) {
        if (featureStore != null
                && featureStore.getAtr() != null
                && featureStore.getAtr().compareTo(BigDecimal.ZERO) > 0) {
            return featureStore.getAtr();
        }
        return DEFAULT_ATR;
    }

    private BigDecimal maxNonNull(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.max(b);
    }

    private BigDecimal minNonNull(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.min(b);
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