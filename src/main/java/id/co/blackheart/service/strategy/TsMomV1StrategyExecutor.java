package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.RiskSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.strategy.VolatilitySnapshot;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TsMomV1StrategyExecutor implements StrategyExecutor {

    private static final String STRATEGY_CODE = "TSMOM_V1";
    private static final String STRATEGY_NAME = "Time Series Momentum";
    private static final String STRATEGY_VERSION = "v1";

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_TREND = "TREND";
    private static final String SIGNAL_TYPE_POSITION_MANAGEMENT = "POSITION_MANAGEMENT";

    private static final String POSITION_ROLE_RUNNER = "RUNNER";

    private static final BigDecimal RUNNER_BREAK_EVEN_R = new BigDecimal("1.00");
    private static final BigDecimal RUNNER_TRAIL_PHASE_1_R = new BigDecimal("2.00");
    private static final BigDecimal RUNNER_TRAIL_PHASE_2_R = new BigDecimal("3.00");

    private static final BigDecimal RUNNER_TRAIL_ATR_PHASE_1 = new BigDecimal("1.20");
    private static final BigDecimal RUNNER_TRAIL_ATR_PHASE_2 = new BigDecimal("0.80");

    private static final BigDecimal RUNNER_LOCK_R_PHASE_1 = new BigDecimal("0.50");
    private static final BigDecimal RUNNER_LOCK_R_PHASE_2 = new BigDecimal("1.50");

    private static final String SETUP_LONG_RUNNER_TRAIL = "TSMOM_LONG_RUNNER_TRAIL";
    private static final String SETUP_SHORT_RUNNER_TRAIL = "TSMOM_SHORT_RUNNER_TRAIL";

    private static final String SETUP_LONG = "TSMOM_LONG_CONTINUATION";
    private static final String SETUP_SHORT = "TSMOM_SHORT_CONTINUATION";
    private static final String SETUP_LONG_BREAK_EVEN = "TSMOM_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BREAK_EVEN = "TSMOM_SHORT_BREAK_EVEN";

    private static final String EXIT_STRUCTURE = "TP1_RUNNER";
    private static final String TARGET_ALL = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final BigDecimal DEFAULT_STOP_ATR_MULT = new BigDecimal("1.40");
    private static final BigDecimal DEFAULT_TP1_R = new BigDecimal("1.20");
    private static final BigDecimal DEFAULT_TP2_R = new BigDecimal("2.20");
    private static final BigDecimal DEFAULT_TP3_R = new BigDecimal("3.60");
    private static final BigDecimal DEFAULT_BREAK_EVEN_R = new BigDecimal("1.00");

    private static final String SOURCE_LIVE = "live";
    private static final String SOURCE_BACKTEST = "backtest";
    private static final BigDecimal MIN_BTC_NOTIONAL = new BigDecimal("0.00001");

    private static final BigDecimal DEFAULT_MIN_SIGNAL_SCORE = new BigDecimal("0.55");
    private static final BigDecimal DEFAULT_MIN_CONFIDENCE_SCORE = new BigDecimal("0.55");

    @Override
    public StrategyRequirements getRequirements() {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval("4h")
                .requireRegimeSnapshot(true)
                .requireVolatilitySnapshot(true)
                .requireRiskSnapshot(true)
                .requireMarketQualitySnapshot(true)
                .build();
    }

    @Override

    public StrategyDecision execute(EnrichedStrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(context, "Invalid context or missing market/feature data");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore feature = context.getFeatureStore();
        PositionSnapshot snapshot = context.getPositionSnapshot();

        BigDecimal closePrice = safe(marketData.getClosePrice());
        if (closePrice.compareTo(ZERO) <= 0) {
            return hold(context, "Close price is invalid");
        }

        if (isMarketVetoed(context)) {
            return veto("Market vetoed by quality or jump-risk filter", context);
        }

        if (context.hasTradablePosition() && snapshot != null) {
            return manageOpenPosition(context, snapshot);
        }

        if (context.isLongAllowed()) {
            StrategyDecision longDecision = tryBuildLongEntry(context, marketData, feature);
            if (longDecision != null) {
                return longDecision;
            }
        }

        if (context.isShortAllowed()) {
            StrategyDecision shortDecision = tryBuildShortEntry(context, marketData, feature);
            if (shortDecision != null) {
                return shortDecision;
            }
        }

        return hold(context, "No qualified TSMOM setup");
    }

    private StrategyDecision manageOpenPosition(
            EnrichedStrategyContext context,
            PositionSnapshot snapshot
    ) {
        String side = snapshot.getSide();
        if (side == null || snapshot.getEntryPrice() == null || snapshot.getCurrentStopLossPrice() == null) {
            return hold(context, "Open position exists but management inputs are incomplete");
        }

        boolean isRunner = snapshot.getPositionRole() != null
                && POSITION_ROLE_RUNNER.equalsIgnoreCase(snapshot.getPositionRole());

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            return isRunner
                    ? manageLongRunnerPosition(context, context.getMarketData(), context.getFeatureStore(), snapshot)
                    : manageLongPosition(context, context.getMarketData(), snapshot);
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            return isRunner
                    ? manageShortRunnerPosition(context, context.getMarketData(), context.getFeatureStore(), snapshot)
                    : manageShortPosition(context, context.getMarketData(), snapshot);
        }

        return hold(context, "Unknown open position side");
    }

    private StrategyDecision manageLongRunnerPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice = safe(snapshot.getEntryPrice());
        BigDecimal currentStop = safe(snapshot.getCurrentStopLossPrice());
        BigDecimal closePrice = safe(marketData.getClosePrice());
        BigDecimal atr = resolveAtr(feature);

        BigDecimal initialStop = snapshot.getInitialStopLossPrice() != null
                ? snapshot.getInitialStopLossPrice()
                : currentStop;

        BigDecimal initialRisk = entryPrice.subtract(initialStop);
        if (initialRisk.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid long runner risk structure");
        }

        BigDecimal move = closePrice.subtract(entryPrice);
        if (move.compareTo(ZERO) <= 0) {
            return hold(context, "Long runner not yet in profit");
        }

        BigDecimal rMultiple = move.divide(initialRisk, 6, RoundingMode.HALF_UP);

        BigDecimal candidateStop = null;
        String reason = null;
        String setupType = null;

        if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_2_R) >= 0) {
            BigDecimal atrStop = closePrice.subtract(atr.multiply(RUNNER_TRAIL_ATR_PHASE_2));
            BigDecimal lockedProfitStop = entryPrice.add(initialRisk.multiply(RUNNER_LOCK_R_PHASE_2));
            candidateStop = atrStop.max(lockedProfitStop).max(entryPrice);
            reason = "Trail long runner aggressively after 3R+";
            setupType = SETUP_LONG_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_1_R) >= 0) {
            BigDecimal atrStop = closePrice.subtract(atr.multiply(RUNNER_TRAIL_ATR_PHASE_1));
            BigDecimal lockedProfitStop = entryPrice.add(initialRisk.multiply(RUNNER_LOCK_R_PHASE_1));
            candidateStop = atrStop.max(lockedProfitStop).max(entryPrice);
            reason = "Trail long runner after 2R+";
            setupType = SETUP_LONG_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_BREAK_EVEN_R) >= 0) {
            candidateStop = entryPrice;
            reason = "Move long runner stop to break-even after 1R";
            setupType = SETUP_LONG_BREAK_EVEN;
        }

        if (candidateStop == null) {
            return hold(context, "Long runner not ready for trailing update");
        }

        if (candidateStop.compareTo(currentStop) <= 0 || candidateStop.compareTo(closePrice) >= 0) {
            return hold(context, "Long runner stop already optimal");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(setupType)
                .side(SIDE_LONG)
                .reason(reason)
                .stopLossPrice(candidateStop)
                .trailingStopPrice(candidateStop)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "TSMOM", "LONG", "RUNNER_TRAIL"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk,
                        "rMultiple", rMultiple,
                        "candidateStop", candidateStop,
                        "positionRole", snapshot.getPositionRole()
                ))
                .build();
    }


    private StrategyDecision manageShortRunnerPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice = safe(snapshot.getEntryPrice());
        BigDecimal currentStop = safe(snapshot.getCurrentStopLossPrice());
        BigDecimal closePrice = safe(marketData.getClosePrice());
        BigDecimal atr = resolveAtr(feature);

        BigDecimal initialStop = snapshot.getInitialStopLossPrice() != null
                ? snapshot.getInitialStopLossPrice()
                : currentStop;

        BigDecimal initialRisk = initialStop.subtract(entryPrice);
        if (initialRisk.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid short runner risk structure");
        }

        BigDecimal move = entryPrice.subtract(closePrice);
        if (move.compareTo(ZERO) <= 0) {
            return hold(context, "Short runner not yet in profit");
        }

        BigDecimal rMultiple = move.divide(initialRisk, 6, RoundingMode.HALF_UP);

        BigDecimal candidateStop = null;
        String reason = null;
        String setupType = null;

        if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_2_R) >= 0) {
            BigDecimal atrStop = closePrice.add(atr.multiply(RUNNER_TRAIL_ATR_PHASE_2));
            BigDecimal lockedProfitStop = entryPrice.subtract(initialRisk.multiply(RUNNER_LOCK_R_PHASE_2));
            candidateStop = atrStop.min(lockedProfitStop).min(entryPrice);
            reason = "Trail short runner aggressively after 3R+";
            setupType = SETUP_SHORT_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_1_R) >= 0) {
            BigDecimal atrStop = closePrice.add(atr.multiply(RUNNER_TRAIL_ATR_PHASE_1));
            BigDecimal lockedProfitStop = entryPrice.subtract(initialRisk.multiply(RUNNER_LOCK_R_PHASE_1));
            candidateStop = atrStop.min(lockedProfitStop).min(entryPrice);
            reason = "Trail short runner after 2R+";
            setupType = SETUP_SHORT_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_BREAK_EVEN_R) >= 0) {
            candidateStop = entryPrice;
            reason = "Move short runner stop to break-even after 1R";
            setupType = SETUP_SHORT_BREAK_EVEN;
        }

        if (candidateStop == null) {
            return hold(context, "Short runner not ready for trailing update");
        }

        if (candidateStop.compareTo(currentStop) >= 0 || candidateStop.compareTo(closePrice) <= 0) {
            return hold(context, "Short runner stop already optimal");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(setupType)
                .side(SIDE_SHORT)
                .reason(reason)
                .stopLossPrice(candidateStop)
                .trailingStopPrice(candidateStop)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "TSMOM", "SHORT", "RUNNER_TRAIL"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk,
                        "rMultiple", rMultiple,
                        "candidateStop", candidateStop,
                        "positionRole", snapshot.getPositionRole()
                ))
                .build();
    }



    private boolean isMarketVetoed(EnrichedStrategyContext context) {
        if (context.getMarketQualitySnapshot() != null
                && Boolean.FALSE.equals(context.getMarketQualitySnapshot().getTradable())) {
            return true;
        }

        VolatilitySnapshot volatilitySnapshot = context.getVolatilitySnapshot();
        if (volatilitySnapshot != null
                && volatilitySnapshot.getJumpRiskScore() != null
                && context.getRuntimeConfig() != null
                && context.getRuntimeConfig().getMaxJumpRiskScore() != null
                && volatilitySnapshot.getJumpRiskScore().compareTo(context.getRuntimeConfig().getMaxJumpRiskScore()) > 0) {
            return true;
        }

        return false;
    }

    private StrategyDecision tryBuildLongEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        if (!isBullishTrend(context, feature, marketData)) {
            return null;
        }

        if (!isBullishEntryConfirmation(feature, marketData)) {
            return null;
        }

        BigDecimal entryPrice = safe(marketData.getClosePrice());
        BigDecimal atr = resolveAtr(feature);
        BigDecimal stopLoss = entryPrice.subtract(atr.multiply(resolveStopAtrMult(context)));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);

        if (riskPerUnit.compareTo(ZERO) <= 0) {
            return null;
        }

        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(resolveTp1R(context)));
        BigDecimal tp2 = entryPrice.add(riskPerUnit.multiply(resolveTp2R(context)));
        BigDecimal tp3 = entryPrice.add(riskPerUnit.multiply(resolveTp3R(context)));

        BigDecimal signalScore = calculateLongSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(resolveMinConfidenceScore(context)) < 0) {
            return null;
        }

        BigDecimal notionalSize = calculateEntryNotional(context, SIDE_LONG);
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated long notional size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_TREND)
                .setupType(SETUP_LONG)
                .side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("Qualified bullish time-series momentum continuation setup")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(tp2)
                .takeProfitPrice3(tp3)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "TSMOM", "LONG", "TREND"))
                .diagnostics(Map.of(
                        "module", "TsMomV1StrategyExecutor",
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "tp1", tp1,
                        "tp2", tp2,
                        "tp3", tp3,
                        "signalScore", signalScore,
                        "confidenceScore", confidenceScore
                ))
                .build();
    }

    private StrategyDecision tryBuildShortEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        if (!isBearishTrend(context, feature, marketData)) {
            return null;
        }

        if (!isBearishEntryConfirmation(feature, marketData)) {
            return null;
        }

        BigDecimal entryPrice = safe(marketData.getClosePrice());
        BigDecimal atr = resolveAtr(feature);
        BigDecimal stopLoss = entryPrice.add(atr.multiply(resolveStopAtrMult(context)));
        BigDecimal riskPerUnit = stopLoss.subtract(entryPrice);

        if (riskPerUnit.compareTo(ZERO) <= 0) {
            return null;
        }

        BigDecimal tp1 = entryPrice.subtract(riskPerUnit.multiply(resolveTp1R(context)));
        BigDecimal tp2 = entryPrice.subtract(riskPerUnit.multiply(resolveTp2R(context)));
        BigDecimal tp3 = entryPrice.subtract(riskPerUnit.multiply(resolveTp3R(context)));

        BigDecimal signalScore = calculateShortSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(resolveMinConfidenceScore(context)) < 0) {
            return null;
        }


        BigDecimal notionalSize = calculateEntryNotional(context, SIDE_SHORT);
        log.info("Calculated short quote notional: {}", notionalSize);
        BigDecimal positionSize = calculateShortTradeAmount(context.getAssetBalance(), context.getAccount());
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated short quote notional is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_TREND)
                .setupType(SETUP_SHORT)
                .side(SIDE_SHORT)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("Qualified bearish time-series momentum continuation setup")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .positionSize(positionSize)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(tp2)
                .takeProfitPrice3(tp3)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "TSMOM", "SHORT", "TREND"))
                .diagnostics(Map.of(
                        "module", "TsMomV1StrategyExecutor",
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "tp1", tp1,
                        "tp2", tp2,
                        "tp3", tp3,
                        "signalScore", signalScore,
                        "confidenceScore", confidenceScore
                ))
                .build();
    }


    private BigDecimal calculateShortTradeAmount(BigDecimal btcBalance, Account account) {
        if (btcBalance == null || account == null || account.getRiskAmount() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal tradeAmount = btcBalance
                .multiply(account.getRiskAmount())
                .setScale(8, RoundingMode.DOWN);

        return tradeAmount.compareTo(MIN_BTC_NOTIONAL) < 0 ? MIN_BTC_NOTIONAL : tradeAmount;
    }


    private StrategyDecision manageLongPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice = safe(snapshot.getEntryPrice());
        BigDecimal currentStop = safe(snapshot.getCurrentStopLossPrice());
        BigDecimal closePrice = safe(marketData.getClosePrice());

        BigDecimal initialRisk = entryPrice.subtract(currentStop);
        if (initialRisk.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid long risk structure");
        }

        BigDecimal move = closePrice.subtract(entryPrice);
        BigDecimal breakEvenTrigger = initialRisk.multiply(resolveBreakEvenR(context));

        if (move.compareTo(breakEvenTrigger) < 0) {
            return hold(context, "Long trade not ready for management update");
        }

        BigDecimal breakEvenStop = entryPrice;
        if (currentStop.compareTo(breakEvenStop) >= 0) {
            return hold(context, "Long stop already at or above break-even");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(SETUP_LONG_BREAK_EVEN)
                .side(SIDE_LONG)
                .reason("Move long stop to break-even after threshold")
                .stopLossPrice(breakEvenStop)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "TSMOM", "LONG", "BREAK_EVEN"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk
                ))
                .build();
    }

    private StrategyDecision manageShortPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice = safe(snapshot.getEntryPrice());
        BigDecimal currentStop = safe(snapshot.getCurrentStopLossPrice());
        BigDecimal closePrice = safe(marketData.getClosePrice());

        BigDecimal initialRisk = currentStop.subtract(entryPrice);
        if (initialRisk.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid short risk structure");
        }

        BigDecimal move = entryPrice.subtract(closePrice);
        BigDecimal breakEvenTrigger = initialRisk.multiply(resolveBreakEvenR(context));

        if (move.compareTo(breakEvenTrigger) < 0) {
            return hold(context, "Short trade not ready for management update");
        }

        BigDecimal breakEvenStop = entryPrice;
        if (currentStop.compareTo(breakEvenStop) <= 0) {
            return hold(context, "Short stop already at or below break-even");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(SETUP_SHORT_BREAK_EVEN)
                .side(SIDE_SHORT)
                .reason("Move short stop to break-even after threshold")
                .stopLossPrice(breakEvenStop)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "TSMOM", "SHORT", "BREAK_EVEN"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk
                ))
                .build();
    }

    private boolean isBullishTrend(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket = context.getBiasMarketData();

        boolean currentTrend = hasValue(marketData.getClosePrice())
                && hasValue(feature.getEma50())
                && hasValue(feature.getEma200())
                && hasValue(feature.getEma50Slope())
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0
                && feature.getEma50().compareTo(feature.getEma200()) > 0
                && feature.getEma50Slope().compareTo(ZERO) > 0
                && !"RANGE".equalsIgnoreCase(feature.getTrendRegime());

        boolean biasTrend = biasFeature == null || biasMarket == null || (
                hasValue(biasMarket.getClosePrice())
                        && hasValue(biasFeature.getEma50())
                        && hasValue(biasFeature.getEma200())
                        && biasMarket.getClosePrice().compareTo(biasFeature.getEma50()) > 0
                        && biasFeature.getEma50().compareTo(biasFeature.getEma200()) > 0
                        && !"RANGE".equalsIgnoreCase(biasFeature.getTrendRegime())
        );

        return currentTrend && biasTrend;
    }

    private boolean isBearishTrend(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket = context.getBiasMarketData();

        boolean currentTrend = hasValue(marketData.getClosePrice())
                && hasValue(feature.getEma50())
                && hasValue(feature.getEma200())
                && hasValue(feature.getEma50Slope())
                && marketData.getClosePrice().compareTo(feature.getEma50()) < 0
                && feature.getEma50().compareTo(feature.getEma200()) < 0
                && feature.getEma50Slope().compareTo(ZERO) < 0
                && !"RANGE".equalsIgnoreCase(feature.getTrendRegime());

        boolean biasTrend = biasFeature == null || biasMarket == null || (
                hasValue(biasMarket.getClosePrice())
                        && hasValue(biasFeature.getEma50())
                        && hasValue(biasFeature.getEma200())
                        && biasMarket.getClosePrice().compareTo(biasFeature.getEma50()) < 0
                        && biasFeature.getEma50().compareTo(biasFeature.getEma200()) < 0
                        && !"RANGE".equalsIgnoreCase(biasFeature.getTrendRegime())
        );

        return currentTrend && biasTrend;
    }

    private boolean isBullishEntryConfirmation(FeatureStore feature, MarketData marketData) {
        return hasValue(feature.getMacdHistogram())
                && hasValue(feature.getRsi())
                && hasValue(feature.getAdx())
                && hasValue(marketData.getClosePrice())
                && hasValue(feature.getEma50())
                && feature.getMacdHistogram().compareTo(ZERO) > 0
                && feature.getRsi().compareTo(new BigDecimal("50")) >= 0
                && feature.getAdx().compareTo(new BigDecimal("18")) >= 0
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0;
    }

    private boolean isBearishEntryConfirmation(FeatureStore feature, MarketData marketData) {
        return hasValue(feature.getMacdHistogram())
                && hasValue(feature.getRsi())
                && hasValue(feature.getAdx())
                && hasValue(marketData.getClosePrice())
                && hasValue(feature.getEma50())
                && feature.getMacdHistogram().compareTo(ZERO) < 0
                && feature.getRsi().compareTo(new BigDecimal("50")) <= 0
                && feature.getAdx().compareTo(new BigDecimal("18")) >= 0
                && marketData.getClosePrice().compareTo(feature.getEma50()) < 0;
    }

    private BigDecimal calculateLongSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        BigDecimal score = new BigDecimal("0.35");

        if (hasValue(feature.getAdx()) && feature.getAdx().compareTo(new BigDecimal("18")) >= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(feature.getRsi()) && feature.getRsi().compareTo(new BigDecimal("52")) >= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(feature.getMacdHistogram()) && feature.getMacdHistogram().compareTo(ZERO) > 0) {
            score = score.add(new BigDecimal("0.15"));
        }

        if (hasValue(feature.getEma50Slope()) && feature.getEma50Slope().compareTo(ZERO) > 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(marketData.getClosePrice()) && hasValue(feature.getEma50())
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (context.getRegimeSnapshot() != null
                && context.getRegimeSnapshot().getTrendScore() != null
                && context.getRegimeSnapshot().getTrendScore().compareTo(ZERO) > 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (context.getMarketQualitySnapshot() != null
                && context.getMarketQualitySnapshot().getVolumeScore() != null
                && context.getMarketQualitySnapshot().getVolumeScore().compareTo(new BigDecimal("0.80")) >= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        BigDecimal score = new BigDecimal("0.35");

        if (hasValue(feature.getAdx()) && feature.getAdx().compareTo(new BigDecimal("18")) >= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(feature.getRsi()) && feature.getRsi().compareTo(new BigDecimal("48")) <= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(feature.getMacdHistogram()) && feature.getMacdHistogram().compareTo(ZERO) < 0) {
            score = score.add(new BigDecimal("0.15"));
        }

        if (hasValue(feature.getEma50Slope()) && feature.getEma50Slope().compareTo(ZERO) < 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(marketData.getClosePrice()) && hasValue(feature.getEma50())
                && marketData.getClosePrice().compareTo(feature.getEma50()) < 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (context.getRegimeSnapshot() != null
                && context.getRegimeSnapshot().getTrendScore() != null
                && context.getRegimeSnapshot().getTrendScore().compareTo(ZERO) > 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (context.getMarketQualitySnapshot() != null
                && context.getMarketQualitySnapshot().getVolumeScore() != null
                && context.getMarketQualitySnapshot().getVolumeScore().compareTo(new BigDecimal("0.80")) >= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateConfidenceScore(EnrichedStrategyContext context, BigDecimal signalScore) {
        BigDecimal confidence = safe(signalScore);

        BigDecimal regimeContribution = resolveRegimeScore(context).multiply(new BigDecimal("0.20"));
        BigDecimal riskContribution = resolveRiskMultiplier(context).multiply(new BigDecimal("0.10"));

        confidence = confidence.add(regimeContribution).add(riskContribution);

        if (resolveJumpRisk(context).compareTo(new BigDecimal("0.50")) > 0) {
            confidence = confidence.subtract(new BigDecimal("0.15"));
        }

        if (confidence.compareTo(ZERO) < 0) {
            return ZERO;
        }

        return confidence.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateLongNotionalSize(EnrichedStrategyContext context) {
        BigDecimal cashBalance = safe(context.getCashBalance());
        BigDecimal riskPct = resolveRiskPct(context);

        if (cashBalance.compareTo(ZERO) <= 0 || riskPct.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        return cashBalance.multiply(riskPct).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateEntryNotional(EnrichedStrategyContext context, String side) {
        if (context == null || side == null || side.isBlank()) {
            return ZERO;
        }

        BigDecimal riskPerTradePct = context.getRiskSnapshot() != null
                ? context.getRiskSnapshot().getFinalRiskPct()
                : null;

        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0) {
            riskPerTradePct = context.getRuntimeConfig() != null
                    ? context.getRuntimeConfig().getRiskPerTradePct()
                    : null;
        }

        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0) {
            riskPerTradePct = context.getAccount() != null
                    ? context.getAccount().getRiskAmount()
                    : null;
        }

        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        String source = resolveExecutionSource(context);

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            BigDecimal cashBalance = context.getCashBalance();
            if (cashBalance == null || cashBalance.compareTo(ZERO) <= 0) {
                return ZERO;
            }

            return cashBalance.multiply(riskPerTradePct).setScale(8, RoundingMode.HALF_UP);
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            if (SOURCE_LIVE.equalsIgnoreCase(source)) {
                BigDecimal assetBalance = context.getAssetBalance();
                BigDecimal price = context.getMarketData() != null ? context.getMarketData().getClosePrice() : null;

                if (assetBalance == null || assetBalance.compareTo(ZERO) <= 0
                        || price == null || price.compareTo(ZERO) <= 0) {
                    return ZERO;
                }

                BigDecimal sellableNotional = assetBalance.multiply(price);
                return sellableNotional.multiply(riskPerTradePct).setScale(8, RoundingMode.HALF_UP);
            }

            BigDecimal cashBalance = context.getCashBalance();
            if (cashBalance == null || cashBalance.compareTo(ZERO) <= 0) {
                return ZERO;
            }

            return cashBalance.multiply(riskPerTradePct).setScale(8, RoundingMode.HALF_UP);
        }

        return ZERO;
    }

    private String resolveExecutionSource(EnrichedStrategyContext context) {
        String source = context.getExecutionMetadata("source", String.class);
        if (source == null || source.isBlank()) {
            return SOURCE_BACKTEST;
        }
        return source;
    }

    private BigDecimal resolveAtr(FeatureStore feature) {
        if (feature != null && feature.getAtr() != null && feature.getAtr().compareTo(ZERO) > 0) {
            return feature.getAtr();
        }
        return ONE;
    }

    private BigDecimal resolveStopAtrMult(EnrichedStrategyContext context) {
        BigDecimal value = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("stopAtrMult")
                : null;
        return value != null && value.compareTo(ZERO) > 0 ? value : DEFAULT_STOP_ATR_MULT;
    }

    private BigDecimal resolveTp1R(EnrichedStrategyContext context) {
        BigDecimal value = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("tp1R")
                : null;
        return value != null && value.compareTo(ZERO) > 0 ? value : DEFAULT_TP1_R;
    }

    private BigDecimal resolveTp2R(EnrichedStrategyContext context) {
        BigDecimal value = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("tp2R")
                : null;
        return value != null && value.compareTo(ZERO) > 0 ? value : DEFAULT_TP2_R;
    }

    private BigDecimal resolveTp3R(EnrichedStrategyContext context) {
        BigDecimal value = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("tp3R")
                : null;
        return value != null && value.compareTo(ZERO) > 0 ? value : DEFAULT_TP3_R;
    }

    private BigDecimal resolveBreakEvenR(EnrichedStrategyContext context) {
        BigDecimal value = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("breakEvenR")
                : null;
        return value != null && value.compareTo(ZERO) > 0 ? value : DEFAULT_BREAK_EVEN_R;
    }

    private BigDecimal resolveMinSignalScore(EnrichedStrategyContext context) {
        BigDecimal value = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getMinSignalScore()
                : null;
        return value != null ? value : DEFAULT_MIN_SIGNAL_SCORE;
    }

    private BigDecimal resolveMinConfidenceScore(EnrichedStrategyContext context) {
        return DEFAULT_MIN_CONFIDENCE_SCORE;
    }

    private BigDecimal resolveRiskPct(EnrichedStrategyContext context) {
        RiskSnapshot riskSnapshot = context.getRiskSnapshot();
        if (riskSnapshot != null
                && riskSnapshot.getFinalRiskPct() != null
                && riskSnapshot.getFinalRiskPct().compareTo(ZERO) > 0) {
            return riskSnapshot.getFinalRiskPct();
        }

        if (context.getAccount() != null
                && context.getAccount().getRiskAmount() != null
                && context.getAccount().getRiskAmount().compareTo(ZERO) > 0) {
            return context.getAccount().getRiskAmount();
        }

        return ZERO;
    }

    private BigDecimal resolveRiskMultiplier(EnrichedStrategyContext context) {
        RiskSnapshot riskSnapshot = context.getRiskSnapshot();
        if (riskSnapshot != null && riskSnapshot.getRiskMultiplier() != null) {
            return riskSnapshot.getRiskMultiplier();
        }
        return ONE;
    }

    private BigDecimal resolveRegimeScore(EnrichedStrategyContext context) {
        RegimeSnapshot regimeSnapshot = context.getRegimeSnapshot();
        if (regimeSnapshot != null && regimeSnapshot.getTrendScore() != null) {
            return regimeSnapshot.getTrendScore();
        }
        return ZERO;
    }

    private BigDecimal resolveJumpRisk(EnrichedStrategyContext context) {
        VolatilitySnapshot volatilitySnapshot = context.getVolatilitySnapshot();
        if (volatilitySnapshot != null && volatilitySnapshot.getJumpRiskScore() != null) {
            return volatilitySnapshot.getJumpRiskScore();
        }
        return ZERO;
    }

    private String resolveRegimeLabel(EnrichedStrategyContext context, FeatureStore feature) {
        if (context.getRegimeSnapshot() != null && context.getRegimeSnapshot().getRegimeLabel() != null) {
            return context.getRegimeSnapshot().getRegimeLabel();
        }
        return feature != null ? feature.getTrendRegime() : null;
    }

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType("TSMOM")
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", "TSMOM"))
                .build();
    }

    private StrategyDecision veto(String vetoReason, EnrichedStrategyContext context) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .regimeLabel(context != null && context.getRegimeSnapshot() != null
                        ? context.getRegimeSnapshot().getRegimeLabel()
                        : null)
                .vetoed(Boolean.TRUE)
                .vetoReason(vetoReason)
                .reason("Decision vetoed by risk layer")
                .jumpRiskScore(resolveJumpRisk(context))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", "TSMOM", "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private boolean hasValue(BigDecimal value) {
        return value != null;
    }
}