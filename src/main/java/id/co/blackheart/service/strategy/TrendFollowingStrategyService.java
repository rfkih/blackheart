package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.projection.TrendFollowingConfigProjection;
import id.co.blackheart.repository.StrategyConfigRepository;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrendFollowingStrategyService {

    private final StrategyConfigRepository strategyConfigRepository;

    public static final String STRATEGY_NAME = "TREND_FOLLOWING";

    public static final String SIDE_LONG = "LONG";
    public static final String SIDE_SHORT = "SHORT";

    private static final String EXIT_STOP_LOSS = "STOP_LOSS";
    private static final String EXIT_TAKE_PROFIT = "TAKE_PROFIT";
    private static final String EXIT_REGIME_REVERSAL = "REGIME_REVERSAL";
    private static final String EXIT_MOMENTUM_BREAKDOWN = "MOMENTUM_BREAKDOWN";

    public StrategyDecision execute(StrategyContext context) {
        if (context == null
                || context.getMarketData() == null
                || context.getFeatureStore() == null
                || context.getUser() == null) {
            return hold(null, "Context / marketData / featureStore / user is null");
        }

        String interval = resolveInterval(context);
        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();
        String asset = context.getAsset();

        if (marketData.getClosePrice() == null) {
            return hold(interval, "Close price is null");
        }

        if (interval == null || interval.isBlank()) {
            return hold(null, "Interval is null");
        }

        if (!interval.equalsIgnoreCase(featureStore.getInterval())) {
            return hold(
                    interval,
                    "Feature interval mismatch. expected=" + interval + ", actual=" + featureStore.getInterval()
            );
        }

        Optional<TrendFollowingConfigProjection> configOpt = findConfig(interval, asset);
        if (configOpt.isEmpty()) {
            return hold(interval, "No active trend following config found");
        }

        TrendFollowingConfigProjection config = configOpt.get();

        if (hasOpenPosition(positionSnapshot)) {
            return evaluateOpenTrade(context, interval, config);
        }

        return evaluateNewEntry(context, interval, config);
    }

    private Optional<TrendFollowingConfigProjection> findConfig(String interval, String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            Optional<TrendFollowingConfigProjection> exact =
                    strategyConfigRepository.findActiveBySymbol(STRATEGY_NAME, interval, symbol);
            if (exact.isPresent()) {
                return exact;
            }
        }

        return strategyConfigRepository.findActiveDefault(STRATEGY_NAME, interval);
    }

    private StrategyDecision evaluateNewEntry(
            StrategyContext context,
            String interval,
            TrendFollowingConfigProjection config
    ) {
        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        String asset = context.getAsset();

        BigDecimal closePrice = marketData.getClosePrice();

        boolean bullishRegime = isBullishRegime(featureStore, closePrice);
        boolean strongTrend = hasStrongTrend(featureStore, config);
        boolean bullishMomentum = hasBullishMomentum(featureStore);
        boolean acceptableVolume = hasAcceptableVolume(featureStore, config);
        boolean validLongTrigger = isValidLongTrigger(featureStore, config);

        log.info(
                "Long entry check | asset={} strategy={} interval={} configId={} bullishRegime={} strongTrend={} bullishMomentum={} acceptableVolume={} validLongTrigger={}",
                asset,
                STRATEGY_NAME,
                interval,
                config.getStrategyConfigId(),
                bullishRegime,
                strongTrend,
                bullishMomentum,
                acceptableVolume,
                validLongTrigger
        );

        if (Boolean.TRUE.equals(config.getAllowLong())
                && bullishRegime
                && strongTrend
                && bullishMomentum
                && acceptableVolume
                && validLongTrigger) {

            Optional<BigDecimal> atrOpt = getValidAtr(featureStore);
            if (atrOpt.isEmpty()) {
                return hold(interval, "ATR invalid for long entry");
            }

            BigDecimal atr = atrOpt.get();
            BigDecimal stopLossPrice = closePrice.subtract(atr.multiply(config.getStopAtrMultiplier()));
            BigDecimal takeProfitPrice = closePrice.add(atr.multiply(config.getTakeProfitAtrMultiplier()));

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
        boolean strongTrendForShort = hasStrongTrend(featureStore, config);
        boolean bearishMomentum = hasBearishMomentum(featureStore);
        boolean acceptableVolumeForShort = hasAcceptableVolume(featureStore, config);
        boolean validShortTrigger = isValidShortTrigger(featureStore, config);

        log.info(
                "Short entry check | asset={} strategy={} interval={} configId={} bearishRegime={} strongTrend={} bearishMomentum={} acceptableVolume={} validShortTrigger={}",
                asset,
                STRATEGY_NAME,
                interval,
                config.getStrategyConfigId(),
                bearishRegime,
                strongTrendForShort,
                bearishMomentum,
                acceptableVolumeForShort,
                validShortTrigger
        );

        if (Boolean.TRUE.equals(config.getAllowShort())
                && bearishRegime
                && strongTrendForShort
                && bearishMomentum
                && acceptableVolumeForShort
                && validShortTrigger) {

            Optional<BigDecimal> atrOpt = getValidAtr(featureStore);
            if (atrOpt.isEmpty()) {
                return hold(interval, "ATR invalid for short entry");
            }

            BigDecimal atr = atrOpt.get();
            BigDecimal stopLossPrice = closePrice.add(atr.multiply(config.getStopAtrMultiplier()));
            BigDecimal takeProfitPrice = closePrice.subtract(atr.multiply(config.getTakeProfitAtrMultiplier()));

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

    private StrategyDecision evaluateOpenTrade(
            StrategyContext context,
            String interval,
            TrendFollowingConfigProjection config
    ) {
        PositionSnapshot position = context.getPositionSnapshot();
        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        String asset = context.getAsset();

        BigDecimal closePrice = marketData.getClosePrice();

        if (SIDE_LONG.equalsIgnoreCase(position.getSide())) {
            String exitReason = getLongExitReason(position, marketData, featureStore);
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

            BigDecimal trailingStop = calculateTrailingStop(position, closePrice, featureStore, config);
            if (shouldRaiseLongStop(position, trailingStop)) {
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

            log.info("Open LONG remains valid | strategy={} interval={} asset={} configId={} close={} stop={} takeProfit={}",
                    STRATEGY_NAME,
                    interval,
                    asset,
                    config.getStrategyConfigId(),
                    closePrice,
                    position.getCurrentStopLossPrice(),
                    position.getTakeProfitPrice());

            return hold(interval, "Open long remains valid");
        }

        if (SIDE_SHORT.equalsIgnoreCase(position.getSide())) {
            String exitReason = getShortExitReason(position, marketData, featureStore);
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

            BigDecimal trailingStop = calculateTrailingStop(position, closePrice, featureStore, config);
            if (shouldLowerShortStop(position, trailingStop)) {
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

            log.info("Open SHORT remains valid | strategy={} interval={} asset={} configId={} close={} stop={} takeProfit={}",
                    STRATEGY_NAME,
                    interval,
                    asset,
                    config.getStrategyConfigId(),
                    closePrice,
                    position.getCurrentStopLossPrice(),
                    position.getTakeProfitPrice());

            return hold(interval, "Open short remains valid");
        }

        return hold(interval, "Unknown trade side");
    }

    private String getLongExitReason(PositionSnapshot position, MarketData marketData, FeatureStore featureStore) {
        BigDecimal closePrice = marketData.getClosePrice();

        boolean stopLossHit = position.getCurrentStopLossPrice() != null
                && closePrice.compareTo(position.getCurrentStopLossPrice()) <= 0;

        boolean takeProfitHit = position.getTakeProfitPrice() != null
                && closePrice.compareTo(position.getTakeProfitPrice()) >= 0;

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

    private String getShortExitReason(PositionSnapshot position, MarketData marketData, FeatureStore featureStore) {
        BigDecimal closePrice = marketData.getClosePrice();

        boolean stopLossHit = position.getCurrentStopLossPrice() != null
                && closePrice.compareTo(position.getCurrentStopLossPrice()) >= 0;

        boolean takeProfitHit = position.getTakeProfitPrice() != null
                && closePrice.compareTo(position.getTakeProfitPrice()) <= 0;

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

    private BigDecimal calculateTrailingStop(
            PositionSnapshot position,
            BigDecimal closePrice,
            FeatureStore featureStore,
            TrendFollowingConfigProjection config
    ) {
        if (position == null
                || featureStore.getAtr() == null
                || featureStore.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal trailingDistance = featureStore.getAtr().multiply(config.getTrailingAtrMultiplier());

        if (SIDE_LONG.equalsIgnoreCase(position.getSide())) {
            return closePrice.subtract(trailingDistance);
        }

        if (SIDE_SHORT.equalsIgnoreCase(position.getSide())) {
            return closePrice.add(trailingDistance);
        }

        return null;
    }

    private boolean shouldRaiseLongStop(PositionSnapshot position, BigDecimal candidateStop) {
        return candidateStop != null
                && (position.getCurrentStopLossPrice() == null
                || candidateStop.compareTo(position.getCurrentStopLossPrice()) > 0);
    }

    private boolean shouldLowerShortStop(PositionSnapshot position, BigDecimal candidateStop) {
        return candidateStop != null
                && (position.getCurrentStopLossPrice() == null
                || candidateStop.compareTo(position.getCurrentStopLossPrice()) < 0);
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

    private boolean hasStrongTrend(FeatureStore f, TrendFollowingConfigProjection config) {
        return f.getAdx() != null
                && config.getMinAdx() != null
                && f.getAdx().compareTo(config.getMinAdx()) >= 0
                && f.getEfficiencyRatio20() != null
                && config.getMinEfficiencyRatio() != null
                && f.getEfficiencyRatio20().compareTo(config.getMinEfficiencyRatio()) >= 0;
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

    private boolean hasAcceptableVolume(FeatureStore f, TrendFollowingConfigProjection config) {
        return f.getRelativeVolume20() == null
                || (config.getMinRelativeVolume() != null
                && f.getRelativeVolume20().compareTo(config.getMinRelativeVolume()) >= 0);
    }

    private boolean isValidLongTrigger(FeatureStore f, TrendFollowingConfigProjection config) {
        boolean breakout = Boolean.TRUE.equals(config.getAllowBreakoutEntry())
                && Boolean.TRUE.equals(f.getIsBullishBreakout());

        boolean pullback = Boolean.TRUE.equals(config.getAllowPullbackEntry())
                && Boolean.TRUE.equals(f.getIsBullishPullback());

        boolean bias = Boolean.TRUE.equals(config.getAllowBiasEntry())
                && SIDE_LONG.equalsIgnoreCase(f.getEntryBias());

        return breakout || pullback || bias;
    }

    private boolean isValidShortTrigger(FeatureStore f, TrendFollowingConfigProjection config) {
        boolean breakout = Boolean.TRUE.equals(config.getAllowBreakoutEntry())
                && Boolean.TRUE.equals(f.getIsBearishBreakout());

        boolean pullback = Boolean.TRUE.equals(config.getAllowPullbackEntry())
                && Boolean.TRUE.equals(f.getIsBearishPullback());

        boolean bias = Boolean.TRUE.equals(config.getAllowBiasEntry())
                && SIDE_SHORT.equalsIgnoreCase(f.getEntryBias());

        return breakout || pullback || bias;
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

    private boolean hasOpenPosition(PositionSnapshot positionSnapshot) {
        return positionSnapshot != null && positionSnapshot.isHasOpenPosition();
    }

    private String resolveInterval(StrategyContext context) {
        if (context.getInterval() != null && !context.getInterval().isBlank()) {
            return context.getInterval();
        }

        if (context.getFeatureStore() != null) {
            return context.getFeatureStore().getInterval();
        }

        return null;
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