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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrendPullbackSingleExitStrategyService implements StrategyExecutor {

    public static final String STRATEGY_CODE = "TREND_PULLBACK_SINGLE_EXIT";
    public static final String STRATEGY_NAME = "TREND_PULLBACK_SINGLE_EXIT";
    public static final String STRATEGY_VERSION = "v1";

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SOURCE_LIVE = "live";
    private static final String SOURCE_BACKTEST = "backtest";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String TARGET_ALL = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final BigDecimal TAKE_PROFIT_R = new BigDecimal("1.50");
    private static final BigDecimal BREAK_EVEN_TRIGGER_R = new BigDecimal("1.00");
    private static final BigDecimal STOP_BUFFER_ATR = new BigDecimal("0.50");

    @Override
    public StrategyRequirements getRequirements() {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval("4h")
                .requireRegimeSnapshot(false)
                .requireVolatilitySnapshot(false)
                .requireRiskSnapshot(false)
                .requireMarketQualitySnapshot(false)
                .build();
    }

    @Override
    public StrategyDecision execute(EnrichedStrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(context, "Invalid context or missing market/feature data");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore feature = context.getFeatureStore();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();

        if (!context.hasTradablePosition() || positionSnapshot == null) {
            return handleNoActiveTrade(context, marketData, feature);
        }

        return handleActiveTrade(context, marketData, positionSnapshot);
    }

    private StrategyDecision handleNoActiveTrade(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket = context.getBiasMarketData();

        boolean longAllowed = context.isLongAllowed();
        boolean shortAllowed = context.isShortAllowed();

        boolean bullishTrend = isBullishTrendV2(feature, marketData);
        boolean bullishPullback = isBullishPullbackSignal(feature);
        boolean bullishBias = isBullishBiasAlignedV2(biasFeature, biasMarket);
        int bullishScore = bullishSupportScore(feature);
        boolean bullishScorePass = bullishScore >= 2;
        boolean validLongSetup = longAllowed
                && bullishTrend
                && bullishPullback
                && bullishBias
                && bullishScorePass;

        boolean bearishTrend = isBearishTrendV2(feature, marketData);
        boolean bearishPullback = isBearishPullbackSignal(feature);
        boolean bearishBias = isBearishBiasAlignedV2(biasFeature, biasMarket);
        int bearishScore = bearishSupportScore(feature);
        boolean bearishScorePass = bearishScore >= 2;
        boolean validShortSetup = shortAllowed
                && bearishTrend
                && bearishPullback
                && bearishBias
                && bearishScorePass;

        if (longAllowed && validLongSetup) {
            log.info(
                    "OPEN_LONG selected | time={} asset={} interval={} close={} bullishScore={}",
                    marketData.getEndTime(),
                    context != null ? context.getAsset() : null,
                    context != null ? context.getInterval() : null,
                    marketData.getClosePrice(),
                    bullishScore
            );
            return buildOpenLongDecision(context, marketData, feature);
        }

        if (shortAllowed && validShortSetup) {
            log.info(
                    "OPEN_SHORT selected | time={} asset={} interval={} close={} bearishScore={}",
                    marketData.getEndTime(),
                    context != null ? context.getAsset() : null,
                    context != null ? context.getInterval() : null,
                    marketData.getClosePrice(),
                    bearishScore
            );
            return buildOpenShortDecision(context, marketData, feature);
        }

        return hold(context, "No valid entry setup");
    }

    private StrategyDecision handleActiveTrade(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        if (snapshot.getEntryPrice() == null || snapshot.getCurrentStopLossPrice() == null) {
            return hold(context, "Missing entry price or stop loss for management");
        }

        String side = snapshot.getSide();
        if (side == null) {
            return hold(context, "Position side is null");
        }

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            return buildLongManagementDecision(context, marketData, snapshot);
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            return buildShortManagementDecision(context, marketData, snapshot);
        }

        return hold(context, "Unknown active position side");
    }

    private StrategyDecision buildOpenLongDecision(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        BigDecimal entryPrice = marketData.getClosePrice();
        BigDecimal stopLoss = calculateLongStopLoss(marketData, feature);
        BigDecimal takeProfit = calculateLongTakeProfit(entryPrice, stopLoss);
        BigDecimal notionalSize = calculateEntryNotional(context, SIDE_LONG);

        if (!isValidRiskStructure(entryPrice, stopLoss, takeProfit, true)) {
            return hold(context, "Invalid long risk structure");
        }

        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated long notional size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType("TREND_PULLBACK")
                .setupType("BULLISH_PULLBACK_SINGLE_EXIT")
                .side(SIDE_LONG)
                .regimeLabel(feature.getTrendRegime())
                .reason("Bullish trend pullback setup confirmed")
                .signalScore(new BigDecimal("0.70"))
                .confidenceScore(new BigDecimal("0.70"))
                .notionalSize(notionalSize)
                .positionSize(null)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(takeProfit)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_SINGLE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "TREND_PULLBACK", "LONG", "SINGLE_EXIT"))
                .diagnostics(Map.of(
                        "strategy", STRATEGY_CODE,
                        "source", resolveExecutionSource(context),
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "takeProfit", takeProfit,
                        "notionalSize", notionalSize
                ))
                .build();
    }

    private StrategyDecision buildOpenShortDecision(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        BigDecimal entryPrice = marketData.getClosePrice();
        BigDecimal stopLoss = calculateShortStopLoss(marketData, feature);
        BigDecimal takeProfit = calculateShortTakeProfit(entryPrice, stopLoss);
        BigDecimal notionalSize = calculateEntryNotional(context, SIDE_SHORT);

        if (!isValidRiskStructure(entryPrice, stopLoss, takeProfit, false)) {
            return hold(context, "Invalid short risk structure");
        }

        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated short notional size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType("TREND_PULLBACK")
                .setupType("BEARISH_PULLBACK_SINGLE_EXIT")
                .side(SIDE_SHORT)
                .regimeLabel(feature.getTrendRegime())
                .reason("Bearish trend pullback setup confirmed")
                .signalScore(new BigDecimal("0.70"))
                .confidenceScore(new BigDecimal("0.70"))
                .notionalSize(notionalSize)
                .positionSize(null)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(takeProfit)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_SINGLE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "TREND_PULLBACK", "SHORT", "SINGLE_EXIT"))
                .diagnostics(Map.of(
                        "strategy", STRATEGY_CODE,
                        "source", resolveExecutionSource(context),
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "takeProfit", takeProfit,
                        "notionalSize", notionalSize
                ))
                .build();
    }

    private StrategyDecision buildLongManagementDecision(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice = snapshot.getEntryPrice();
        BigDecimal currentStop = snapshot.getCurrentStopLossPrice();
        BigDecimal closePrice = marketData.getClosePrice();

        BigDecimal initialRisk = entryPrice.subtract(currentStop);
        if (initialRisk.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid long risk structure");
        }

        BigDecimal currentMove = closePrice.subtract(entryPrice);
        BigDecimal breakEvenTrigger = initialRisk.multiply(BREAK_EVEN_TRIGGER_R);

        if (currentMove.compareTo(breakEvenTrigger) < 0) {
            return hold(context, "Long trade not ready for break-even update");
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
                .signalType("POSITION_MANAGEMENT")
                .setupType("LONG_BREAK_EVEN_UPDATE")
                .side(SIDE_LONG)
                .reason("Move long stop to break-even after 1R")
                .stopLossPrice(breakEvenStop)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "LONG", "BREAK_EVEN"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk
                ))
                .build();
    }

    private StrategyDecision buildShortManagementDecision(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice = snapshot.getEntryPrice();
        BigDecimal currentStop = snapshot.getCurrentStopLossPrice();
        BigDecimal closePrice = marketData.getClosePrice();

        BigDecimal initialRisk = currentStop.subtract(entryPrice);
        if (initialRisk.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid short risk structure");
        }

        BigDecimal currentMove = entryPrice.subtract(closePrice);
        BigDecimal breakEvenTrigger = initialRisk.multiply(BREAK_EVEN_TRIGGER_R);

        if (currentMove.compareTo(breakEvenTrigger) < 0) {
            return hold(context, "Short trade not ready for break-even update");
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
                .signalType("POSITION_MANAGEMENT")
                .setupType("SHORT_BREAK_EVEN_UPDATE")
                .side(SIDE_SHORT)
                .reason("Move short stop to break-even after 1R")
                .stopLossPrice(breakEvenStop)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "SHORT", "BREAK_EVEN"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk
                ))
                .build();
    }

    private boolean isValidLongSetup(EnrichedStrategyContext context, MarketData marketData, FeatureStore feature) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket = context.getBiasMarketData();

        return isBullishTrendV2(feature, marketData)
                && isBullishPullbackSignal(feature)
                && isBullishBiasAlignedV2(biasFeature, biasMarket)
                && bullishSupportScore(feature) >= 2;
    }

    private boolean isValidShortSetup(EnrichedStrategyContext context, MarketData marketData, FeatureStore feature) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket = context.getBiasMarketData();

        return isBearishTrendV2(feature, marketData)
                && isBearishPullbackSignal(feature)
                && isBearishBiasAlignedV2(biasFeature, biasMarket)
                && bearishSupportScore(feature) >= 2;
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

    private boolean isBullishTrendV2(FeatureStore feature, MarketData marketData) {
        return hasValue(marketData.getClosePrice())
                && hasValue(feature.getEma50())
                && hasValue(feature.getEma200())
                && hasValue(feature.getEma50Slope())
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0
                && feature.getEma50().compareTo(feature.getEma200()) > 0
                && feature.getEma50Slope().compareTo(ZERO) > 0
                && !"RANGE".equalsIgnoreCase(feature.getTrendRegime());
    }

    private boolean isBearishTrendV2(FeatureStore feature, MarketData marketData) {
        return hasValue(marketData.getClosePrice())
                && hasValue(feature.getEma50())
                && hasValue(feature.getEma200())
                && hasValue(feature.getEma50Slope())
                && marketData.getClosePrice().compareTo(feature.getEma50()) < 0
                && feature.getEma50().compareTo(feature.getEma200()) < 0
                && feature.getEma50Slope().compareTo(ZERO) < 0
                && !"RANGE".equalsIgnoreCase(feature.getTrendRegime());
    }

    private boolean isBullishPullbackSignal(FeatureStore feature) {
        return Boolean.TRUE.equals(feature.getIsBullishPullback());
    }

    private boolean isBearishPullbackSignal(FeatureStore feature) {
        return Boolean.TRUE.equals(feature.getIsBearishPullback());
    }

    private boolean isBullishBiasAlignedV2(FeatureStore bias, MarketData biasMarket) {
        if (bias == null || biasMarket == null) {
            return true;
        }

        return hasValue(biasMarket.getClosePrice())
                && hasValue(bias.getEma50())
                && hasValue(bias.getEma200())
                && biasMarket.getClosePrice().compareTo(bias.getEma50()) > 0
                && bias.getEma50().compareTo(bias.getEma200()) > 0
                && !"RANGE".equalsIgnoreCase(bias.getTrendRegime());
    }

    private boolean isBearishBiasAlignedV2(FeatureStore bias, MarketData biasMarket) {
        if (bias == null || biasMarket == null) {
            return true;
        }

        return hasValue(biasMarket.getClosePrice())
                && hasValue(bias.getEma50())
                && hasValue(bias.getEma200())
                && biasMarket.getClosePrice().compareTo(bias.getEma50()) < 0
                && bias.getEma50().compareTo(bias.getEma200()) < 0
                && !"RANGE".equalsIgnoreCase(bias.getTrendRegime());
    }

    private int bullishSupportScore(FeatureStore feature) {
        int score = 0;

        if (hasValue(feature.getAdx()) && feature.getAdx().compareTo(new BigDecimal("18")) >= 0) {
            score++;
        }

        if (hasValue(feature.getPlusDI()) && hasValue(feature.getMinusDI())
                && feature.getPlusDI().compareTo(feature.getMinusDI()) > 0) {
            score++;
        }

        if (hasValue(feature.getRsi()) && feature.getRsi().compareTo(new BigDecimal("50")) >= 0) {
            score++;
        }

        if (hasValue(feature.getMacdHistogram()) && feature.getMacdHistogram().compareTo(ZERO) > 0) {
            score++;
        }

        if (hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(new BigDecimal("0.35")) >= 0) {
            score++;
        }

        if (hasValue(feature.getCloseLocationValue())
                && feature.getCloseLocationValue().compareTo(new BigDecimal("0.50")) >= 0) {
            score++;
        }

        if (hasValue(feature.getRelativeVolume20())
                && feature.getRelativeVolume20().compareTo(new BigDecimal("0.80")) >= 0) {
            score++;
        }

        return score;
    }

    private int bearishSupportScore(FeatureStore feature) {
        int score = 0;

        if (hasValue(feature.getAdx()) && feature.getAdx().compareTo(new BigDecimal("18")) >= 0) {
            score++;
        }

        if (hasValue(feature.getPlusDI()) && hasValue(feature.getMinusDI())
                && feature.getMinusDI().compareTo(feature.getPlusDI()) > 0) {
            score++;
        }

        if (hasValue(feature.getRsi()) && feature.getRsi().compareTo(new BigDecimal("50")) <= 0) {
            score++;
        }

        if (hasValue(feature.getMacdHistogram()) && feature.getMacdHistogram().compareTo(ZERO) < 0) {
            score++;
        }

        if (hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(new BigDecimal("0.35")) >= 0) {
            score++;
        }

        if (hasValue(feature.getCloseLocationValue())
                && feature.getCloseLocationValue().compareTo(new BigDecimal("0.50")) <= 0) {
            score++;
        }

        if (hasValue(feature.getRelativeVolume20())
                && feature.getRelativeVolume20().compareTo(new BigDecimal("0.80")) >= 0) {
            score++;
        }

        return score;
    }

    private BigDecimal calculateLongStopLoss(MarketData marketData, FeatureStore feature) {
        BigDecimal atr = safe(feature.getAtr());
        BigDecimal structureLow = firstNonNull(feature.getLowestLow20(), marketData.getLowPrice());
        BigDecimal buffer = atr.multiply(STOP_BUFFER_ATR);
        return structureLow.subtract(buffer);
    }

    private BigDecimal calculateShortStopLoss(MarketData marketData, FeatureStore feature) {
        BigDecimal atr = safe(feature.getAtr());
        BigDecimal structureHigh = firstNonNull(feature.getHighestHigh20(), marketData.getHighPrice());
        BigDecimal buffer = atr.multiply(STOP_BUFFER_ATR);
        return structureHigh.add(buffer);
    }

    private BigDecimal calculateLongTakeProfit(BigDecimal entryPrice, BigDecimal stopLoss) {
        BigDecimal risk = entryPrice.subtract(stopLoss);
        return entryPrice.add(risk.multiply(TAKE_PROFIT_R));
    }

    private BigDecimal calculateShortTakeProfit(BigDecimal entryPrice, BigDecimal stopLoss) {
        BigDecimal risk = stopLoss.subtract(entryPrice);
        return entryPrice.subtract(risk.multiply(TAKE_PROFIT_R));
    }

    private boolean isValidRiskStructure(
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            boolean isLong
    ) {
        if (!hasValue(entryPrice) || !hasValue(stopLoss) || !hasValue(takeProfit)) {
            return false;
        }

        if (isLong) {
            if (stopLoss.compareTo(entryPrice) >= 0) {
                return false;
            }
            if (takeProfit.compareTo(entryPrice) <= 0) {
                return false;
            }
        } else {
            if (stopLoss.compareTo(entryPrice) <= 0) {
                return false;
            }
            if (takeProfit.compareTo(entryPrice) >= 0) {
                return false;
            }
        }

        BigDecimal risk = isLong ? entryPrice.subtract(stopLoss) : stopLoss.subtract(entryPrice);
        return risk.compareTo(ZERO) > 0;
    }

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType("TREND_PULLBACK")
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", "TREND_PULLBACK"))
                .build();
    }

    private boolean hasValue(BigDecimal value) {
        return value != null;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
    }
}