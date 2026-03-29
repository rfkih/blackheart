package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.RiskSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.strategy.VolatilitySnapshot;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.TradeConstant.DecisionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component("RAHT_V1")
public class RahtV1StrategyExecutor implements StrategyExecutor {

    private static final String STRATEGY_CODE = "RAHT_V1";
    private static final String STRATEGY_NAME = "Regime Adaptive Hierarchical Trend";
    private static final String STRATEGY_VERSION = "v1";

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_TREND_PULLBACK = "TREND_PULLBACK";
    private static final String SIGNAL_TYPE_POSITION_MANAGEMENT = "POSITION_MANAGEMENT";

    private static final String SETUP_LONG = "BULL_PULLBACK_RECLAIM";
    private static final String SETUP_SHORT = "BEAR_PULLBACK_RECLAIM";
    private static final String SETUP_LONG_BREAK_EVEN = "LONG_BREAK_EVEN_UPDATE";
    private static final String SETUP_SHORT_BREAK_EVEN = "SHORT_BREAK_EVEN_UPDATE";

    private static final String EXIT_STRUCTURE = "TP1_TP2_RUNNER";
    private static final String TARGET_ALL = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final BigDecimal DEFAULT_STOP_ATR_MULT = new BigDecimal("1.50");
    private static final BigDecimal DEFAULT_TP1_R = new BigDecimal("1.50");
    private static final BigDecimal DEFAULT_TP2_R = new BigDecimal("2.50");
    private static final BigDecimal DEFAULT_TP3_R = new BigDecimal("4.00");
    private static final BigDecimal DEFAULT_BREAK_EVEN_R = new BigDecimal("1.00");

    private static final BigDecimal MIN_SIGNAL_SCORE = new BigDecimal("0.55");
    private static final BigDecimal MIN_CONFIDENCE_SCORE = new BigDecimal("0.55");

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
            return manageOpenPosition(context, marketData, snapshot);
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

        return hold(context, "No qualified RAHT setup");
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
                && volatilitySnapshot.getJumpRiskScore()
                .compareTo(context.getRuntimeConfig().getMaxJumpRiskScore()) > 0) {
            return true;
        }

        return false;
    }

    private StrategyDecision tryBuildLongEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        if (!isBullishRegime(context, feature, marketData)) {
            return null;
        }

        if (!isBullishPullbackSignal(feature)) {
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

        BigDecimal signalScore = calculateLongSignalScore(context, feature);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(resolveMinConfidenceScore(context)) < 0) {
            return null;
        }

        BigDecimal notionalSize = calculateNotionalSize(context);

        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated long notional size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_TREND_PULLBACK)
                .setupType(SETUP_LONG)
                .side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("Qualified bullish pullback continuation setup")
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
                .tags(List.of("ENTRY", "RAHT", "TREND", "LONG"))
                .diagnostics(Map.of(
                        "module", "RahtV1StrategyExecutor",
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "tp1", tp1,
                        "tp2", tp2,
                        "tp3", tp3
                ))
                .build();
    }

    private StrategyDecision tryBuildShortEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        if (!isBearishRegime(context, feature, marketData)) {
            return null;
        }

        if (!isBearishPullbackSignal(feature)) {
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

        BigDecimal signalScore = calculateShortSignalScore(context, feature);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(resolveMinConfidenceScore(context)) < 0) {
            return null;
        }

        BigDecimal quoteNotional = calculateShortQuoteNotional(context);
        if (quoteNotional.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated short quote notional is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_TREND_PULLBACK)
                .setupType(SETUP_SHORT)
                .side(SIDE_SHORT)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("Qualified bearish pullback continuation setup")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .positionSize(quoteNotional)
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
                .tags(List.of("ENTRY", "RAHT", "TREND", "SHORT"))
                .diagnostics(Map.of(
                        "module", "RahtV1StrategyExecutor",
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "tp1", tp1,
                        "tp2", tp2,
                        "tp3", tp3
                ))
                .build();
    }

    private StrategyDecision manageOpenPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        String side = snapshot.getSide();
        if (side == null || snapshot.getEntryPrice() == null || snapshot.getCurrentStopLossPrice() == null) {
            return hold(context, "Open position exists but management inputs are incomplete");
        }

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            return manageLongPosition(context, marketData, snapshot);
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            return manageShortPosition(context, marketData, snapshot);
        }

        return hold(context, "Unknown open position side");
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
                .tags(List.of("MANAGEMENT", "RAHT", "LONG", "BREAK_EVEN"))
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
                .tags(List.of("MANAGEMENT", "RAHT", "SHORT", "BREAK_EVEN"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk
                ))
                .build();
    }

    private boolean isBullishRegime(
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

    private boolean isBearishRegime(
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

    private boolean isBullishPullbackSignal(FeatureStore feature) {
        return Boolean.TRUE.equals(feature.getIsBullishPullback());
    }

    private boolean isBearishPullbackSignal(FeatureStore feature) {
        return Boolean.TRUE.equals(feature.getIsBearishPullback());
    }

    private BigDecimal calculateLongSignalScore(EnrichedStrategyContext context, FeatureStore feature) {
        BigDecimal score = new BigDecimal("0.40");

        if (hasValue(feature.getAdx()) && feature.getAdx().compareTo(new BigDecimal("18")) >= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(feature.getRsi()) && feature.getRsi().compareTo(new BigDecimal("50")) >= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(feature.getMacdHistogram()) && feature.getMacdHistogram().compareTo(ZERO) > 0) {
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

        return score.min(ONE);
    }

    private BigDecimal calculateShortSignalScore(EnrichedStrategyContext context, FeatureStore feature) {
        BigDecimal score = new BigDecimal("0.40");

        if (hasValue(feature.getAdx()) && feature.getAdx().compareTo(new BigDecimal("18")) >= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(feature.getRsi()) && feature.getRsi().compareTo(new BigDecimal("50")) <= 0) {
            score = score.add(new BigDecimal("0.10"));
        }

        if (hasValue(feature.getMacdHistogram()) && feature.getMacdHistogram().compareTo(ZERO) < 0) {
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

        return score.min(ONE);
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

    private BigDecimal calculateNotionalSize(EnrichedStrategyContext context) {
        BigDecimal cashBalance = safe(context.getCashBalance());
        BigDecimal riskPct = resolveRiskPct(context);

        if (cashBalance.compareTo(ZERO) <= 0 || riskPct.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        return cashBalance.multiply(riskPct).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortQuoteNotional(EnrichedStrategyContext context) {
        BigDecimal assetBalance = safe(context.getAssetBalance());
        BigDecimal closePrice = context.getMarketData() != null
                ? safe(context.getMarketData().getClosePrice())
                : ZERO;
        BigDecimal riskPct = resolveRiskPct(context);

        if (assetBalance.compareTo(ZERO) <= 0 || closePrice.compareTo(ZERO) <= 0 || riskPct.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        return assetBalance.multiply(closePrice).multiply(riskPct).setScale(8, RoundingMode.HALF_UP);
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
        return value != null ? value : MIN_SIGNAL_SCORE;
    }

    private BigDecimal resolveMinConfidenceScore(EnrichedStrategyContext context) {
        return MIN_CONFIDENCE_SCORE;
    }

    private BigDecimal resolveRiskPct(EnrichedStrategyContext context) {
        RiskSnapshot riskSnapshot = context.getRiskSnapshot();
        if (riskSnapshot != null && riskSnapshot.getFinalRiskPct() != null
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
                .signalType("RAHT")
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", "RAHT"))
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
                .tags(List.of("VETO", "RAHT", "RISK_LAYER"))
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