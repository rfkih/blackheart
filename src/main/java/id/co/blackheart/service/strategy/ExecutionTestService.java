package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.StrategyParamRepository;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
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

    private final StrategyParamRepository strategyParamRepository;

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
    // Same string value as EXIT_STRUCTURE_SINGLE — different semantic namespace
    // (target position role vs exit structure). Linked here to satisfy S1192.
    private static final String TARGET_SINGLE = EXIT_STRUCTURE_SINGLE;
    private static final String TARGET_RUNNER = "RUNNER";

    private static final String SIGNAL_TYPE_EXECUTION_TEST = "EXECUTION_TEST";
    private static final String KEY_EXIT_STRUCTURE = "exitStructure";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
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
        if (ObjectUtils.isEmpty(context) || ObjectUtils.isEmpty(context.getMarketData())) {
            return hold(context, "Invalid context");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();

        BigDecimal closePrice = marketData.getClosePrice();
        if (ObjectUtils.isEmpty(closePrice)) {
            return hold(context, "Close price is null");
        }

        BigDecimal atr = resolveAtr(featureStore);
        if (ObjectUtils.isEmpty(atr)) {
            return hold(context, "ATR unavailable, cannot compute trade levels");
        }
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
        if (context.isLongAllowed()) return buildLongEntry(context, closePrice, atr, featureStore, exitStructure);
        if (context.isShortAllowed()) return buildShortEntry(context, closePrice, atr, featureStore, exitStructure);
        return hold(context, "Both long and short disabled");
    }

    private StrategyDecision buildLongEntry(
            EnrichedStrategyContext context,
            BigDecimal closePrice,
            BigDecimal atr,
            FeatureStore featureStore,
            String exitStructure
    ) {
        BigDecimal stopLoss = closePrice.subtract(atr.multiply(LONG_STOP_ATR));
        BigDecimal tp1 = closePrice.add(atr.multiply(SINGLE_TP_ATR));
        BigDecimal tp2 = closePrice.add(atr.multiply(TP2_ATR));

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_EXECUTION_TEST)
                .setupType("TEST_OPEN_LONG")
                .side(SIDE_LONG)
                .reason("Execution test open long with structure " + exitStructure)
                // Leave notionalSize/positionSize null. The live executor's
                // calculateLongTradeAmount() will compute the actual USDT
                // notional from capital_allocation_pct × USDT balance, with the
                // MIN_USDT_NOTIONAL floor. Hard-coding a value here would
                // override that and either over-trade or fail the balance
                // check (the latter is what was happening with BigDecimal.ONE
                // — 1 BTC required, ~0 BTC available).
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(resolveTakeProfit1(exitStructure, tp1))
                .takeProfitPrice2(resolveTakeProfit2(exitStructure, tp2))
                .takeProfitPrice3(null)
                .exitStructure(exitStructure)
                .targetPositionRole(TARGET_ALL)
                .entryAtr(atr)
                .entryAdx(adxOrNull(featureStore))
                .entryRsi(rsiOrNull(featureStore))
                .entryTrendRegime(regimeOrNull(featureStore))
                .regimeLabel(regimeOrNull(featureStore))
                .signalScore(BigDecimal.ONE)
                .confidenceScore(BigDecimal.ONE)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("TEST", "ENTRY", SIDE_LONG))
                .diagnostics(Map.of(
                        "module", "ExecutionTestService",
                        KEY_EXIT_STRUCTURE, exitStructure
                ))
                .build();
    }

    private StrategyDecision buildShortEntry(
            EnrichedStrategyContext context,
            BigDecimal closePrice,
            BigDecimal atr,
            FeatureStore featureStore,
            String exitStructure
    ) {
        BigDecimal stopLoss = closePrice.add(atr.multiply(SHORT_STOP_ATR));
        BigDecimal tp1 = closePrice.subtract(atr.multiply(SINGLE_TP_ATR));
        BigDecimal tp2 = closePrice.subtract(atr.multiply(TP2_ATR));

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_EXECUTION_TEST)
                .setupType("TEST_OPEN_SHORT")
                .side(SIDE_SHORT)
                .reason("Execution test open short with structure " + exitStructure)
                // Leave positionSize null — see buildLongEntry note. The live
                // executor's calculateShortTradeAmount() sizes from
                // capital_allocation_pct × BTC balance, with the MIN_BTC_NOTIONAL
                // floor. The old hardcoded BigDecimal.ONE meant 1 BTC required
                // on every SHORT entry — guaranteed insufficient-balance silent
                // skip on any realistic account.
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(resolveTakeProfit1(exitStructure, tp1))
                .takeProfitPrice2(resolveTakeProfit2(exitStructure, tp2))
                .takeProfitPrice3(null)
                .exitStructure(exitStructure)
                .targetPositionRole(TARGET_ALL)
                .entryAtr(atr)
                .entryAdx(adxOrNull(featureStore))
                .entryRsi(rsiOrNull(featureStore))
                .entryTrendRegime(regimeOrNull(featureStore))
                .regimeLabel(regimeOrNull(featureStore))
                .signalScore(BigDecimal.ONE)
                .confidenceScore(BigDecimal.ONE)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("TEST", "ENTRY", SIDE_SHORT))
                .diagnostics(Map.of(
                        "module", "ExecutionTestService",
                        KEY_EXIT_STRUCTURE, exitStructure
                ))
                .build();
    }

    private static BigDecimal adxOrNull(FeatureStore fs) {
        return ObjectUtils.isEmpty(fs) ? null : fs.getAdx();
    }

    private static BigDecimal rsiOrNull(FeatureStore fs) {
        return ObjectUtils.isEmpty(fs) ? null : fs.getRsi();
    }

    private static String regimeOrNull(FeatureStore fs) {
        return ObjectUtils.isEmpty(fs) ? null : fs.getTrendRegime();
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
                        .signalType(SIGNAL_TYPE_EXECUTION_TEST)
                        .setupType("TEST_FORCE_CLOSE_LONG")
                        .side(SIDE_LONG)
                        .exitReason(EXIT_TEST_MANUAL_CLOSE)
                        .reason("Execution test close long")
                        .targetPositionRole(TARGET_ALL)
                        .decisionTime(LocalDateTime.now())
                        .tags(List.of("TEST", "EXIT", SIDE_LONG))
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
                        .signalType(SIGNAL_TYPE_EXECUTION_TEST)
                        .setupType("TEST_FORCE_CLOSE_SHORT")
                        .side(SIDE_SHORT)
                        .exitReason(EXIT_TEST_MANUAL_CLOSE)
                        .reason("Execution test close short")
                        .targetPositionRole(TARGET_ALL)
                        .decisionTime(LocalDateTime.now())
                        .tags(List.of("TEST", "EXIT", SIDE_SHORT))
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
        if (ObjectUtils.isEmpty(entryPrice) || ObjectUtils.isEmpty(atr) || atr.compareTo(ZERO) <= 0) {
            return hold(context, "Long management skipped due to invalid inputs");
        }

        BigDecimal move = closePrice.subtract(entryPrice);
        if (move.compareTo(atr.multiply(BREAK_EVEN_TRIGGER_ATR)) < 0) {
            return hold(context, "Long trade not ready for management update");
        }

        BigDecimal currentStop = positionSnapshot.getCurrentStopLossPrice();
        BigDecimal breakEvenStop = entryPrice;
        BigDecimal trailingStop = closePrice.subtract(atr.multiply(TRAIL_DISTANCE_ATR));

        boolean trailActive = move.compareTo(atr.multiply(TRAIL_TRIGGER_ATR)) >= 0;
        BigDecimal updatedStop = maxNonNull(currentStop, breakEvenStop);

        if (trailActive) {
            updatedStop = maxNonNull(updatedStop, trailingStop);
        }

        BigDecimal currentTp = positionSnapshot.getTakeProfitPrice();
        BigDecimal updatedTp = currentTp;

        if (TARGET_SINGLE.equalsIgnoreCase(positionSnapshot.getPositionRole())
                || "TP1".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = closePrice.add(atr.multiply(EXTEND_TP_ATR));
        } else if ("TP2".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = closePrice.add(atr.multiply(new BigDecimal("1.5")));
        } else if (TARGET_RUNNER.equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = null;
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_EXECUTION_TEST)
                .setupType("TEST_LONG_MANAGEMENT")
                .side(SIDE_LONG)
                .reason("Execution test long management update")
                .stopLossPrice(updatedStop)
                .trailingStopPrice(trailActive ? trailingStop : null)
                .takeProfitPrice1(resolveManagementTp1(positionSnapshot, updatedTp))
                .takeProfitPrice2(resolveManagementTp2(positionSnapshot, updatedTp))
                .takeProfitPrice3(null)
                .targetPositionRole(resolveTargetRole(positionSnapshot, exitStructure))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("TEST", "MANAGEMENT", SIDE_LONG))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "closePrice", closePrice,
                        "atr", atr,
                        KEY_EXIT_STRUCTURE, exitStructure
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

        boolean trailActive = move.compareTo(atr.multiply(TRAIL_TRIGGER_ATR)) >= 0;
        BigDecimal updatedStop = minNonNull(currentStop, breakEvenStop);

        if (trailActive) {
            updatedStop = minNonNull(updatedStop, trailingStop);
        }

        BigDecimal currentTp = positionSnapshot.getTakeProfitPrice();
        BigDecimal updatedTp = currentTp;

        if (TARGET_SINGLE.equalsIgnoreCase(positionSnapshot.getPositionRole())
                || "TP1".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = closePrice.subtract(atr.multiply(EXTEND_TP_ATR));
        } else if ("TP2".equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = closePrice.subtract(atr.multiply(new BigDecimal("1.5")));
        } else if (TARGET_RUNNER.equalsIgnoreCase(positionSnapshot.getPositionRole())) {
            updatedTp = null;
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_EXECUTION_TEST)
                .setupType("TEST_SHORT_MANAGEMENT")
                .side(SIDE_SHORT)
                .reason("Execution test short management update")
                .stopLossPrice(updatedStop)
                .trailingStopPrice(trailActive ? trailingStop : null)
                .takeProfitPrice1(resolveManagementTp1(positionSnapshot, updatedTp))
                .takeProfitPrice2(resolveManagementTp2(positionSnapshot, updatedTp))
                .takeProfitPrice3(null)
                .targetPositionRole(resolveTargetRole(positionSnapshot, exitStructure))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("TEST", "MANAGEMENT", SIDE_SHORT))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "closePrice", closePrice,
                        "atr", atr,
                        KEY_EXIT_STRUCTURE, exitStructure
                ))
                .build();
    }

    private String resolveExitStructure(EnrichedStrategyContext context) {
        // Test-only: an active strategy_param preset can pin the exit structure
        // so the operator can exercise RUNNER_ONLY (no supported live interval
        // maps to it) and force any structure on any interval for coverage.
        String override = resolveExitStructureOverride(context);
        if (ObjectUtils.isNotEmpty(override)) {
            return override;
        }

        String interval = ObjectUtils.isNotEmpty(context) ? context.getInterval() : null;
        if (ObjectUtils.isEmpty(interval)) {
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

    private String resolveExitStructureOverride(EnrichedStrategyContext context) {
        if (ObjectUtils.isEmpty(context) || ObjectUtils.isEmpty(context.getAccountStrategy())) return null;
        UUID accountStrategyId = context.getAccountStrategy().getAccountStrategyId();
        if (ObjectUtils.isEmpty(accountStrategyId)) return null;
        return strategyParamRepository.findActiveByAccountStrategyId(accountStrategyId)
                .map(p -> {
                    Map<String, Object> overrides = p.getParamOverrides();
                    if (ObjectUtils.isEmpty(overrides)) return null;
                    Object value = overrides.get(KEY_EXIT_STRUCTURE);
                    return ObjectUtils.isNotEmpty(value) ? value.toString().toUpperCase() : null;
                })
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    private BigDecimal resolveTakeProfit1(String exitStructure, BigDecimal tp1) {
        return switch (exitStructure) {
            case EXIT_STRUCTURE_SINGLE, EXIT_STRUCTURE_TP1_RUNNER, EXIT_STRUCTURE_TP1_TP2_RUNNER -> tp1;
            case EXIT_STRUCTURE_RUNNER_ONLY -> null;
            default -> tp1;
        };
    }

    private BigDecimal resolveTakeProfit2(String exitStructure, BigDecimal tp2) {
        return EXIT_STRUCTURE_TP1_TP2_RUNNER.equals(exitStructure) ? tp2 : null;
    }

    private String resolveTargetRole(PositionSnapshot snapshot, String exitStructure) {
        if (ObjectUtils.isEmpty(snapshot) || ObjectUtils.isEmpty(snapshot.getPositionRole())) {
            return TARGET_ALL;
        }

        if (EXIT_STRUCTURE_SINGLE.equalsIgnoreCase(exitStructure)) {
            return TARGET_SINGLE;
        }

        if (TARGET_RUNNER.equalsIgnoreCase(snapshot.getPositionRole())) {
            return TARGET_RUNNER;
        }

        return snapshot.getPositionRole();
    }

    private BigDecimal resolveManagementTp1(PositionSnapshot snapshot, BigDecimal updatedTp) {
        if (ObjectUtils.isEmpty(snapshot) || ObjectUtils.isEmpty(snapshot.getPositionRole())) {
            return updatedTp;
        }

        return switch (snapshot.getPositionRole().toUpperCase()) {
            case EXIT_STRUCTURE_SINGLE, "TP1" -> updatedTp;
            default -> null;
        };
    }

    private BigDecimal resolveManagementTp2(PositionSnapshot snapshot, BigDecimal updatedTp) {
        if (ObjectUtils.isEmpty(snapshot) || ObjectUtils.isEmpty(snapshot.getPositionRole())) {
            return null;
        }

        return "TP2".equalsIgnoreCase(snapshot.getPositionRole()) ? updatedTp : null;
    }

    private BigDecimal resolveAtr(FeatureStore featureStore) {
        if (ObjectUtils.isNotEmpty(featureStore)
                && ObjectUtils.isNotEmpty(featureStore.getAtr())
                && featureStore.getAtr().compareTo(BigDecimal.ZERO) > 0) {
            return featureStore.getAtr();
        }
        return null;
    }

    private BigDecimal maxNonNull(BigDecimal a, BigDecimal b) {
        if (ObjectUtils.isEmpty(a)) {
            return b;
        }
        if (ObjectUtils.isEmpty(b)) {
            return a;
        }
        return a.max(b);
    }

    private BigDecimal minNonNull(BigDecimal a, BigDecimal b) {
        if (ObjectUtils.isEmpty(a)) {
            return b;
        }
        if (ObjectUtils.isEmpty(b)) {
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
                .signalType(SIGNAL_TYPE_EXECUTION_TEST)
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("TEST", "HOLD"))
                .build();
    }
}