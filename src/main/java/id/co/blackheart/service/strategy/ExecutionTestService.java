package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionTestService implements StrategyExecutor {

    public static final String STRATEGY_CODE = "TEST";
    public static final String STRATEGY_NAME = "TEST";
    public static final String STRATEGY_VERSION = "v1";

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
    private static final BigDecimal TP2_ATR = new BigDecimal("3.5");

    private static final BigDecimal BREAK_EVEN_TRIGGER_ATR = new BigDecimal("1.0");
    private static final BigDecimal TRAIL_TRIGGER_ATR = new BigDecimal("2.0");
    private static final BigDecimal TRAIL_DISTANCE_ATR = new BigDecimal("1.2");
    private static final BigDecimal EXTEND_TP_ATR = new BigDecimal("1.0");

    @Override
    public StrategyRequirements getRequirements() {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(false)
                .requireRegimeSnapshot(false)
                .requireVolatilitySnapshot(false)
                .requireRiskSnapshot(false)
                .requireMarketQualitySnapshot(false)
                .build();
    }

    @Override
    public StrategyDecision execute(EnrichedStrategyContext context) {
        if (context == null || context.getMarketData() == null) {
            return hold(context, "Invalid context");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();

        BigDecimal closePrice = marketData.getClosePrice();
        if (closePrice == null) {
            return hold(context, "Close price is null");
        }

        BigDecimal atr = resolveAtr(featureStore);
        String exitStructure = resolveExitStructure(context);

        UUID activeTradeId = context.getExecutionMetadata("activeTradeId", UUID.class);

        log.info(
                "Execution test | interval={} exitStructure={} allowLong={} allowShort={} activeTradeId={} side={} role={} closePrice={}",
                context.getInterval(),
                exitStructure,
                context.isLongAllowed(),
                context.isShortAllowed(),
                activeTradeId,
                positionSnapshot != null ? positionSnapshot.getSide() : null,
                positionSnapshot != null ? positionSnapshot.getPositionRole() : null,
                closePrice
        );

        if (!context.hasTradablePosition() || positionSnapshot == null) {
            return handleNoActiveTrade(context, closePrice, atr, featureStore, exitStructure);
        }

        return handleActiveTrade(context, positionSnapshot, closePrice, atr, exitStructure);
    }

    private StrategyDecision handleNoActiveTrade(
            EnrichedStrategyContext context,
            BigDecimal closePrice,
            BigDecimal atr,
            FeatureStore featureStore,
            String exitStructure
    ) {
        if (context.isLongAllowed()) {
            BigDecimal stopLoss = closePrice.subtract(atr.multiply(LONG_STOP_ATR));
            BigDecimal tp1 = closePrice.add(atr.multiply(SINGLE_TP_ATR));
            BigDecimal tp2 = closePrice.add(atr.multiply(TP2_ATR));

            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_LONG)
                    .strategyCode(STRATEGY_CODE)
                    .strategyName(STRATEGY_NAME)
                    .strategyVersion(STRATEGY_VERSION)
                    .strategyInterval(context.getInterval())
                    .signalType("EXECUTION_TEST")
                    .setupType("TEST_OPEN_LONG")
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
                    .regimeLabel(featureStore != null ? featureStore.getTrendRegime() : null)
                    .signalScore(BigDecimal.ONE)
                    .confidenceScore(BigDecimal.ONE)
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("TEST", "ENTRY", "LONG"))
                    .diagnostics(Map.of(
                            "module", "ExecutionTestService",
                            "exitStructure", exitStructure
                    ))
                    .build();
        }

        if (context.isShortAllowed()) {
            BigDecimal stopLoss = closePrice.add(atr.multiply(SHORT_STOP_ATR));
            BigDecimal tp1 = closePrice.subtract(atr.multiply(SINGLE_TP_ATR));
            BigDecimal tp2 = closePrice.subtract(atr.multiply(TP2_ATR));

            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_SHORT)
                    .strategyCode(STRATEGY_CODE)
                    .strategyName(STRATEGY_NAME)
                    .strategyVersion(STRATEGY_VERSION)
                    .strategyInterval(context.getInterval())
                    .signalType("EXECUTION_TEST")
                    .setupType("TEST_OPEN_SHORT")
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
                    .regimeLabel(featureStore != null ? featureStore.getTrendRegime() : null)
                    .signalScore(BigDecimal.ONE)
                    .confidenceScore(BigDecimal.ONE)
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("TEST", "ENTRY", "SHORT"))
                    .diagnostics(Map.of(
                            "module", "ExecutionTestService",
                            "exitStructure", exitStructure
                    ))
                    .build();
        }

        return hold(context, "Both long and short disabled");
    }

    private StrategyDecision handleActiveTrade(
            EnrichedStrategyContext context,
            PositionSnapshot positionSnapshot,
            BigDecimal closePrice,
            BigDecimal atr,
            String exitStructure
    ) {
        String side = positionSnapshot.getSide();

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            if (!context.isLongAllowed()) {
                return StrategyDecision.builder()
                        .decisionType(DecisionType.CLOSE_LONG)
                        .strategyCode(STRATEGY_CODE)
                        .strategyName(STRATEGY_NAME)
                        .strategyVersion(STRATEGY_VERSION)
                        .strategyInterval(context.getInterval())
                        .signalType("EXECUTION_TEST")
                        .setupType("TEST_FORCE_CLOSE_LONG")
                        .side(SIDE_LONG)
                        .exitReason(EXIT_TEST_MANUAL_CLOSE)
                        .reason("Execution test close long")
                        .decisionTime(LocalDateTime.now())
                        .tags(List.of("TEST", "EXIT", "LONG"))
                        .build();
            }

            return buildLongManagementDecision(context, positionSnapshot, closePrice, atr, exitStructure);
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            if (!context.isShortAllowed()) {
                return StrategyDecision.builder()
                        .decisionType(DecisionType.CLOSE_SHORT)
                        .strategyCode(STRATEGY_CODE)
                        .strategyName(STRATEGY_NAME)
                        .strategyVersion(STRATEGY_VERSION)
                        .strategyInterval(context.getInterval())
                        .signalType("EXECUTION_TEST")
                        .setupType("TEST_FORCE_CLOSE_SHORT")
                        .side(SIDE_SHORT)
                        .exitReason(EXIT_TEST_MANUAL_CLOSE)
                        .reason("Execution test close short")
                        .decisionTime(LocalDateTime.now())
                        .tags(List.of("TEST", "EXIT", "SHORT"))
                        .build();
            }

            return buildShortManagementDecision(context, positionSnapshot, closePrice, atr, exitStructure);
        }

        return hold(context, "Unknown active trade side");
    }

    private StrategyDecision buildLongManagementDecision(
            EnrichedStrategyContext context,
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
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType("EXECUTION_TEST")
                .setupType("TEST_LONG_MANAGEMENT")
                .side(SIDE_LONG)
                .reason("Execution test long management update")
                .stopLossPrice(updatedStop)
                .trailingStopPrice(updatedStop)
                .takeProfitPrice1(resolveManagementTp1(positionSnapshot, updatedTp))
                .takeProfitPrice2(resolveManagementTp2(positionSnapshot, updatedTp))
                .takeProfitPrice3(null)
                .targetPositionRole(resolveTargetRole(positionSnapshot, exitStructure))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("TEST", "MANAGEMENT", "LONG"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "closePrice", closePrice,
                        "atr", atr,
                        "exitStructure", exitStructure
                ))
                .build();
    }

    private StrategyDecision buildShortManagementDecision(
            EnrichedStrategyContext context,
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
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType("EXECUTION_TEST")
                .setupType("TEST_SHORT_MANAGEMENT")
                .side(SIDE_SHORT)
                .reason("Execution test short management update")
                .stopLossPrice(updatedStop)
                .trailingStopPrice(updatedStop)
                .takeProfitPrice1(resolveManagementTp1(positionSnapshot, updatedTp))
                .takeProfitPrice2(resolveManagementTp2(positionSnapshot, updatedTp))
                .takeProfitPrice3(null)
                .targetPositionRole(resolveTargetRole(positionSnapshot, exitStructure))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("TEST", "MANAGEMENT", "SHORT"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "closePrice", closePrice,
                        "atr", atr,
                        "exitStructure", exitStructure
                ))
                .build();
    }

    private String resolveExitStructure(EnrichedStrategyContext context) {
        String interval = context.getInterval();
        if (interval == null) {
            return EXIT_STRUCTURE_SINGLE;
        }

        return switch (interval.toLowerCase()) {
            case "15m" -> EXIT_STRUCTURE_SINGLE;
            case "1h" -> EXIT_STRUCTURE_TP1_RUNNER;
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

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType("EXECUTION_TEST")
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("TEST", "HOLD"))
                .build();
    }
}