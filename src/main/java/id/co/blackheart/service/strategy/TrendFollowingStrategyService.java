package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Trades;
import id.co.blackheart.util.TradeConstant.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrendFollowingStrategyService {

    public static final String STRATEGY_NAME = "TREND_FOLLOWING";

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String EXIT_STOP_LOSS = "STOP_LOSS";
    private static final String EXIT_TAKE_PROFIT = "TAKE_PROFIT";
    private static final String EXIT_REGIME_REVERSAL = "REGIME_REVERSAL";
    private static final String EXIT_MOMENTUM_BREAKDOWN = "MOMENTUM_BREAKDOWN";

    private static final Set<String> SUPPORTED_INTERVALS = Set.of("4h");

    private static final BigDecimal MIN_ADX = new BigDecimal("20");
    private static final BigDecimal MIN_EFFICIENCY_RATIO = new BigDecimal("0.30");
    private static final BigDecimal MIN_RELATIVE_VOLUME = new BigDecimal("0.80");

    private static final BigDecimal STOP_ATR_MULTIPLIER = new BigDecimal("1.8");
    private static final BigDecimal TAKE_PROFIT_ATR_MULTIPLIER = new BigDecimal("3.0");
    private static final BigDecimal TRAILING_ATR_MULTIPLIER = new BigDecimal("2.0");

    public StrategyDecision execute(StrategyContext context) {
        if (context == null
                || context.getMarketData() == null
                || context.getFeatureStore() == null
                || context.getUser() == null) {
            return hold(null, "Context / marketData / featureStore / user is null");
        }

        String interval = context.getInterval();
        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        Trades activeTrade = context.getActiveTrade();

        if (marketData.getClosePrice() == null) {
            return hold(interval, "Close price is null");
        }

        if (interval == null || interval.isBlank()) {
            interval = featureStore.getInterval();
        }

        if (interval == null || interval.isBlank()) {
            return hold(null, "Interval is null");
        }

        if (!supports(interval)) {
            return hold(interval, "Unsupported interval: " + interval);
        }

        if (!interval.equalsIgnoreCase(featureStore.getInterval())) {
            return hold(interval, "Feature interval mismatch. expected=" + interval + ", actual=" + featureStore.getInterval());
        }

        if (activeTrade != null) {
            return evaluateOpenTrade(context, interval);
        }

        return evaluateNewEntry(context, interval);
    }

    public boolean supports(String interval) {
        return interval != null && SUPPORTED_INTERVALS.contains(interval.toLowerCase());
    }

    private StrategyDecision evaluateNewEntry(StrategyContext context, String interval) {
        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        String asset = context.getAsset();

        BigDecimal closePrice = marketData.getClosePrice();

        boolean bullishRegime = isBullishRegime(featureStore, closePrice);
        boolean strongTrend = hasStrongTrend(featureStore);
        boolean bullishMomentum = hasBullishMomentum(featureStore);
        boolean acceptableVolume = hasAcceptableVolume(featureStore);
        boolean validLongTrigger = isValidLongTrigger(featureStore);

        log.info(
                "Long entry check | asset={} strategy={} interval={} bullishRegime={} strongTrend={} bullishMomentum={} acceptableVolume={} validLongTrigger={}",
                asset, STRATEGY_NAME, interval, bullishRegime, strongTrend, bullishMomentum, acceptableVolume, validLongTrigger
        );

        if (bullishRegime && strongTrend && bullishMomentum && acceptableVolume && validLongTrigger) {
            Optional<BigDecimal> atrOpt = getValidAtr(featureStore);
            if (atrOpt.isEmpty()) {
                return hold(interval, "ATR invalid for long entry");
            }

            BigDecimal atr = atrOpt.get();
            BigDecimal stopLossPrice = closePrice.subtract(atr.multiply(STOP_ATR_MULTIPLIER));
            BigDecimal takeProfitPrice = closePrice.add(atr.multiply(TAKE_PROFIT_ATR_MULTIPLIER));

            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_LONG)
                    .strategyName(STRATEGY_NAME)
                    .strategyInterval(interval)
                    .side(SIDE_LONG)
                    .reason("Bullish regime + strong trend + bullish momentum + valid trigger")
                    .positionSize(BigDecimal.ONE)
                    .stopLossPrice(stopLossPrice)
                    .takeProfitPrice(takeProfitPrice)
                    .entryAdx(featureStore.getAdx())
                    .entryAtr(featureStore.getAtr())
                    .entryRsi(featureStore.getRsi())
                    .entryTrendRegime(featureStore.getTrendRegime())
                    .build();
        }

        boolean bearishRegime = isBearishRegime(featureStore, closePrice);
        boolean bearishMomentum = hasBearishMomentum(featureStore);
        boolean validShortTrigger = isValidShortTrigger(featureStore);

        log.info(
                "Short entry check | asset={} strategy={} interval={} bearishRegime={} strongTrend={} bearishMomentum={} acceptableVolume={} validShortTrigger={}",
                asset, STRATEGY_NAME, interval, bearishRegime, strongTrend, bearishMomentum, acceptableVolume, validShortTrigger
        );

        if (bearishRegime && strongTrend && bearishMomentum && acceptableVolume && validShortTrigger) {
            Optional<BigDecimal> atrOpt = getValidAtr(featureStore);
            if (atrOpt.isEmpty()) {
                return hold(interval, "ATR invalid for short entry");
            }

            BigDecimal atr = atrOpt.get();
            BigDecimal stopLossPrice = closePrice.add(atr.multiply(STOP_ATR_MULTIPLIER));
            BigDecimal takeProfitPrice = closePrice.subtract(atr.multiply(TAKE_PROFIT_ATR_MULTIPLIER));

            return StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_SHORT)
                    .strategyName(STRATEGY_NAME)
                    .strategyInterval(interval)
                    .side(SIDE_SHORT)
                    .reason("Bearish regime + strong trend + bearish momentum + valid trigger")
                    .positionSize(BigDecimal.ONE)
                    .stopLossPrice(stopLossPrice)
                    .takeProfitPrice(takeProfitPrice)
                    .entryAdx(featureStore.getAdx())
                    .entryAtr(featureStore.getAtr())
                    .entryRsi(featureStore.getRsi())
                    .entryTrendRegime(featureStore.getTrendRegime())
                    .build();
        }

        return hold(interval, "No valid entry setup");
    }

    private StrategyDecision evaluateOpenTrade(StrategyContext context, String interval) {
        Trades activeTrade = context.getActiveTrade();
        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        String asset = context.getAsset();

        BigDecimal closePrice = marketData.getClosePrice();

        if (SIDE_LONG.equalsIgnoreCase(activeTrade.getSide())) {
            String exitReason = getLongExitReason(activeTrade, marketData, featureStore);
            if (exitReason != null) {
                return StrategyDecision.builder()
                        .decisionType(DecisionType.CLOSE_LONG)
                        .strategyName(STRATEGY_NAME)
                        .strategyInterval(interval)
                        .side(SIDE_LONG)
                        .exitReason(exitReason)
                        .reason("Long exit triggered")
                        .build();
            }

            BigDecimal trailingStop = calculateTrailingStop(activeTrade, closePrice, featureStore);
            if (trailingStop != null
                    && (activeTrade.getCurrentStopLossPrice() == null
                    || trailingStop.compareTo(activeTrade.getCurrentStopLossPrice()) > 0)) {

                return StrategyDecision.builder()
                        .decisionType(DecisionType.UPDATE_TRAILING_STOP)
                        .strategyName(STRATEGY_NAME)
                        .strategyInterval(interval)
                        .side(SIDE_LONG)
                        .trailingStopPrice(trailingStop)
                        .stopLossPrice(trailingStop)
                        .reason("Update long trailing stop")
                        .build();
            }

            log.info("Open LONG remains valid | strategy={} interval={} asset={} close={} stop={} takeProfit={}",
                    STRATEGY_NAME,
                    interval,
                    asset,
                    closePrice,
                    activeTrade.getCurrentStopLossPrice(),
                    activeTrade.getTakeProfitPrice());

            return hold(interval, "Open long remains valid");
        }

        if (SIDE_SHORT.equalsIgnoreCase(activeTrade.getSide())) {
            String exitReason = getShortExitReason(activeTrade, marketData, featureStore);
            if (exitReason != null) {
                return StrategyDecision.builder()
                        .decisionType(DecisionType.CLOSE_SHORT)
                        .strategyName(STRATEGY_NAME)
                        .strategyInterval(interval)
                        .side(SIDE_SHORT)
                        .exitReason(exitReason)
                        .reason("Short exit triggered")
                        .build();
            }

            BigDecimal trailingStop = calculateTrailingStop(activeTrade, closePrice, featureStore);
            if (trailingStop != null
                    && (activeTrade.getCurrentStopLossPrice() == null
                    || trailingStop.compareTo(activeTrade.getCurrentStopLossPrice()) < 0)) {

                return StrategyDecision.builder()
                        .decisionType(DecisionType.UPDATE_TRAILING_STOP)
                        .strategyName(STRATEGY_NAME)
                        .strategyInterval(interval)
                        .side(SIDE_SHORT)
                        .trailingStopPrice(trailingStop)
                        .stopLossPrice(trailingStop)
                        .reason("Update short trailing stop")
                        .build();
            }

            log.info("Open SHORT remains valid | strategy={} interval={} asset={} close={} stop={} takeProfit={}",
                    STRATEGY_NAME,
                    interval,
                    asset,
                    closePrice,
                    activeTrade.getCurrentStopLossPrice(),
                    activeTrade.getTakeProfitPrice());

            return hold(interval, "Open short remains valid");
        }

        return hold(interval, "Unknown trade side");
    }

    private String getLongExitReason(Trades trade, MarketData marketData, FeatureStore featureStore) {
        BigDecimal closePrice = marketData.getClosePrice();

        boolean stopLossHit = trade.getCurrentStopLossPrice() != null
                && closePrice.compareTo(trade.getCurrentStopLossPrice()) <= 0;

        boolean takeProfitHit = trade.getTakeProfitPrice() != null
                && closePrice.compareTo(trade.getTakeProfitPrice()) >= 0;

        boolean regimeBroken = !"BULL".equalsIgnoreCase(featureStore.getTrendRegime());

        boolean momentumBroken = featureStore.getMacdHistogram() != null
                && featureStore.getMacdHistogram().compareTo(BigDecimal.ZERO) < 0
                && featureStore.getEma50() != null
                && closePrice.compareTo(featureStore.getEma50()) < 0;

        if (stopLossHit) return EXIT_STOP_LOSS;
        if (takeProfitHit) return EXIT_TAKE_PROFIT;
        if (regimeBroken) return EXIT_REGIME_REVERSAL;
        if (momentumBroken) return EXIT_MOMENTUM_BREAKDOWN;

        return null;
    }

    private String getShortExitReason(Trades trade, MarketData marketData, FeatureStore featureStore) {
        BigDecimal closePrice = marketData.getClosePrice();

        boolean stopLossHit = trade.getCurrentStopLossPrice() != null
                && closePrice.compareTo(trade.getCurrentStopLossPrice()) >= 0;

        boolean takeProfitHit = trade.getTakeProfitPrice() != null
                && closePrice.compareTo(trade.getTakeProfitPrice()) <= 0;

        boolean regimeBroken = !"BEAR".equalsIgnoreCase(featureStore.getTrendRegime());

        boolean momentumBroken = featureStore.getMacdHistogram() != null
                && featureStore.getMacdHistogram().compareTo(BigDecimal.ZERO) > 0
                && featureStore.getEma50() != null
                && closePrice.compareTo(featureStore.getEma50()) > 0;

        if (stopLossHit) return EXIT_STOP_LOSS;
        if (takeProfitHit) return EXIT_TAKE_PROFIT;
        if (regimeBroken) return EXIT_REGIME_REVERSAL;
        if (momentumBroken) return EXIT_MOMENTUM_BREAKDOWN;

        return null;
    }

    private BigDecimal calculateTrailingStop(Trades activeTrade, BigDecimal closePrice, FeatureStore featureStore) {
        if (featureStore.getAtr() == null || featureStore.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal trailingDistance = featureStore.getAtr().multiply(TRAILING_ATR_MULTIPLIER);

        if (SIDE_LONG.equalsIgnoreCase(activeTrade.getSide())) {
            return closePrice.subtract(trailingDistance);
        }

        if (SIDE_SHORT.equalsIgnoreCase(activeTrade.getSide())) {
            return closePrice.add(trailingDistance);
        }

        return null;
    }

    private boolean isBullishRegime(FeatureStore f, BigDecimal closePrice) {
        return "BULL".equalsIgnoreCase(f.getTrendRegime())
                && f.getEma20() != null
                && f.getEma50() != null
                && f.getEma200() != null
                && f.getEma20().compareTo(f.getEma50()) > 0
                && f.getEma50().compareTo(f.getEma200()) > 0
                && closePrice != null
                && closePrice.compareTo(f.getEma20()) >= 0
                && positiveOrZero(f.getEma50Slope())
                && positiveOrZero(f.getEma200Slope());
    }

    private boolean isBearishRegime(FeatureStore f, BigDecimal closePrice) {
        return "BEAR".equalsIgnoreCase(f.getTrendRegime())
                && f.getEma20() != null
                && f.getEma50() != null
                && f.getEma200() != null
                && f.getEma20().compareTo(f.getEma50()) < 0
                && f.getEma50().compareTo(f.getEma200()) < 0
                && closePrice != null
                && closePrice.compareTo(f.getEma20()) <= 0
                && negativeOrZero(f.getEma50Slope())
                && negativeOrZero(f.getEma200Slope());
    }

    private boolean hasStrongTrend(FeatureStore f) {
        return f.getAdx() != null
                && f.getAdx().compareTo(MIN_ADX) >= 0
                && f.getEfficiencyRatio20() != null
                && f.getEfficiencyRatio20().compareTo(MIN_EFFICIENCY_RATIO) >= 0;
    }

    private boolean hasBullishMomentum(FeatureStore f) {
        return f.getPlusDI() != null
                && f.getMinusDI() != null
                && f.getPlusDI().compareTo(f.getMinusDI()) > 0
                && f.getMacd() != null
                && f.getMacdSignal() != null
                && f.getMacd().compareTo(f.getMacdSignal()) > 0
                && f.getMacdHistogram() != null
                && f.getMacdHistogram().compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean hasBearishMomentum(FeatureStore f) {
        return f.getPlusDI() != null
                && f.getMinusDI() != null
                && f.getPlusDI().compareTo(f.getMinusDI()) < 0
                && f.getMacd() != null
                && f.getMacdSignal() != null
                && f.getMacd().compareTo(f.getMacdSignal()) < 0
                && f.getMacdHistogram() != null
                && f.getMacdHistogram().compareTo(BigDecimal.ZERO) < 0;
    }

    private boolean hasAcceptableVolume(FeatureStore f) {
        return f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(MIN_RELATIVE_VOLUME) >= 0;
    }

    private boolean isValidLongTrigger(FeatureStore f) {
        return Boolean.TRUE.equals(f.getIsBullishBreakout())
                || Boolean.TRUE.equals(f.getIsBullishPullback())
                || "LONG".equalsIgnoreCase(f.getEntryBias());
    }

    private boolean isValidShortTrigger(FeatureStore f) {
        return Boolean.TRUE.equals(f.getIsBearishBreakout())
                || Boolean.TRUE.equals(f.getIsBearishPullback())
                || SIDE_SHORT.equalsIgnoreCase(f.getEntryBias());
    }

    private Optional<BigDecimal> getValidAtr(FeatureStore featureStore) {
        if (featureStore.getAtr() == null || featureStore.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(featureStore.getAtr());
    }

    private boolean positiveOrZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    private boolean negativeOrZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) <= 0;
    }

    private StrategyDecision hold(String interval, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(interval)
                .reason(reason)
                .build();
    }
}