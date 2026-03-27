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
import java.math.RoundingMode;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrendPullbackSingleExitStrategyService implements StrategyExecutor {

    public static final String STRATEGY_NAME = "TREND_PULLBACK_SINGLE_EXIT";

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String TARGET_ALL = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final BigDecimal MIN_ADX = new BigDecimal("20");
    private static final BigDecimal BIAS_MIN_ADX = new BigDecimal("18");

    private static final BigDecimal LONG_MIN_RSI = new BigDecimal("52");
    private static final BigDecimal SHORT_MAX_RSI = new BigDecimal("48");

    private static final BigDecimal MIN_BODY_TO_RANGE = new BigDecimal("0.50");
    private static final BigDecimal MIN_LONG_CLV = new BigDecimal("0.60");
    private static final BigDecimal MAX_SHORT_CLV = new BigDecimal("0.40");

    private static final BigDecimal MIN_RELATIVE_VOLUME = new BigDecimal("0.90");

    private static final BigDecimal STOP_BUFFER_ATR = new BigDecimal("0.50");
    private static final BigDecimal TAKE_PROFIT_R = new BigDecimal("1.50");
    private static final BigDecimal BREAK_EVEN_TRIGGER_R = new BigDecimal("1.00");

    @Override
    public StrategyDecision execute(StrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(context, "Invalid context or missing market/feature data");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore feature = context.getFeatureStore();
        Trades activeTrade = context.getActiveTrade();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();

        if (activeTrade == null || positionSnapshot == null || !positionSnapshot.isHasOpenPosition()) {
            return handleNoActiveTrade(context, marketData, feature);
        }

        return handleActiveTrade(context, marketData, positionSnapshot);
    }

    private boolean isValidShortSetup(StrategyContext context, MarketData marketData, FeatureStore feature) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket = context.getBiasMarketData();

        return isBearishTrendV2(feature, marketData)
                && isBearishPullbackSignal(feature)
                && isBearishBiasAlignedV2(biasFeature, biasMarket)
                && bearishSupportScore(feature) >= 2;
    }

    private StrategyDecision handleNoActiveTrade(
            StrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        if (context.isAllowLong() && isValidLongSetup(context, marketData, feature)) {
            return buildOpenLongDecision(context, marketData, feature);
        }

        if (context.isAllowShort() && isValidShortSetup(context, marketData, feature)) {
            return buildOpenShortDecision(context, marketData, feature);
        }

        return hold(context, "No valid entry setup");
    }

    private StrategyDecision handleActiveTrade(
            StrategyContext context,
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
            StrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        BigDecimal entryPrice = marketData.getClosePrice();
        BigDecimal stopLoss = calculateLongStopLoss(marketData, feature);
        BigDecimal takeProfit = calculateLongTakeProfit(entryPrice, stopLoss);
        BigDecimal positionSize = calculatePositionSize(context, SIDE_LONG);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context.getInterval())
                .side(SIDE_LONG)
                .reason("Bullish trend pullback setup confirmed")
                .positionSize(positionSize)
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
                .build();
    }


    private BigDecimal calculatePositionSize(StrategyContext context, String side) {
        if (context == null || side == null || side.isBlank()) {
            return ZERO;
        }

        BigDecimal riskPerTradePct = context.getRiskPerTradePct();
        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0) {
            return ZERO;
        }



        if ("SHORT".equalsIgnoreCase(side)) {
            BigDecimal assetBalance = context.getAssetBalance();
            BigDecimal price = context.getMarketData() != null ? context.getMarketData().getClosePrice() : null;

            if (assetBalance == null || assetBalance.compareTo(ZERO) <= 0 || price == null || price.compareTo(ZERO) <= 0) {
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

    private StrategyDecision buildOpenShortDecision(
            StrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        BigDecimal entryPrice = marketData.getClosePrice();
        BigDecimal stopLoss = calculateShortStopLoss(marketData, feature);
        BigDecimal takeProfit = calculateShortTakeProfit(entryPrice, stopLoss);
        BigDecimal positionSize = calculatePositionSize(context, SIDE_SHORT);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context.getInterval())
                .side(SIDE_SHORT)
                .reason("Bearish trend pullback setup confirmed")
                .positionSize(positionSize)
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
                .build();
    }

    private StrategyDecision buildLongManagementDecision(
            StrategyContext context,
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
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context.getInterval())
                .side(SIDE_LONG)
                .reason("Move long stop to break-even after 1R")
                .stopLossPrice(breakEvenStop)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .build();
    }

    private StrategyDecision buildShortManagementDecision(
            StrategyContext context,
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
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context.getInterval())
                .side(SIDE_SHORT)
                .reason("Move short stop to break-even after 1R")
                .stopLossPrice(breakEvenStop)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .build();
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

    private boolean isBullishTrend(FeatureStore feature, MarketData marketData) {
        return hasValue(marketData.getClosePrice())
                && hasValue(feature.getEma20())
                && hasValue(feature.getEma50())
                && hasValue(feature.getEma200())
                && hasValue(feature.getEma50Slope())
                && hasValue(feature.getAdx())
                && hasValue(feature.getPlusDI())
                && hasValue(feature.getMinusDI())
                && hasValue(feature.getRsi())
                && hasValue(feature.getMacdHistogram())
                && hasValue(feature.getBodyToRangeRatio())
                && hasValue(feature.getCloseLocationValue())
                && hasValue(feature.getRelativeVolume20())
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0
                && feature.getEma20().compareTo(feature.getEma50()) > 0
                && feature.getEma50().compareTo(feature.getEma200()) > 0
                && feature.getEma50Slope().compareTo(ZERO) > 0
                && safe(feature.getEma200Slope()).compareTo(ZERO) >= 0
                && feature.getAdx().compareTo(MIN_ADX) >= 0
                && feature.getPlusDI().compareTo(feature.getMinusDI()) > 0
                && feature.getRsi().compareTo(LONG_MIN_RSI) >= 0
                && feature.getMacdHistogram().compareTo(ZERO) > 0
                && feature.getBodyToRangeRatio().compareTo(MIN_BODY_TO_RANGE) >= 0
                && feature.getCloseLocationValue().compareTo(MIN_LONG_CLV) >= 0
                && feature.getRelativeVolume20().compareTo(MIN_RELATIVE_VOLUME) >= 0
                && !"BEARISH".equalsIgnoreCase(feature.getEntryBias())
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

    private boolean isValidLongSetup(StrategyContext context, MarketData marketData, FeatureStore feature) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket = context.getBiasMarketData();

        return isBullishTrendV2(feature, marketData)
                && isBullishPullbackSignal(feature)
                && isBullishBiasAlignedV2(biasFeature, biasMarket)
                && bullishSupportScore(feature) >= 2;
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

    private StrategyDecision hold(StrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context != null ? context.getInterval() : null)
                .reason(reason)
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