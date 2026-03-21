package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TimeSeriesMomentumService implements StrategyExecutor {

    public static final String STRATEGY_NAME = "TSMOM";
    public static final String SIDE_LONG = "LONG";

    /**
     * Dynamic profile by interval.
     * You can tune these later.
     */
    private static final Map<String, IntervalProfile> INTERVAL_PROFILES = Map.of(
            "15m", new IntervalProfile(
                    new BigDecimal("25"),
                    new BigDecimal("1.2"),
                    new BigDecimal("2.0")
            ),
            "1h", new IntervalProfile(
                    new BigDecimal("23"),
                    new BigDecimal("1.8"),
                    new BigDecimal("3.0")
            ),
            "4h", new IntervalProfile(
                    new BigDecimal("22"),
                    new BigDecimal("2.5"),
                    new BigDecimal("4.0")
            ),
            "1d", new IntervalProfile(
                    new BigDecimal("20"),
                    new BigDecimal("3.0"),
                    new BigDecimal("5.0")
            )
    );

    @Override
    public StrategyDecision execute(StrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(null, "Invalid context");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();

        String interval = resolveInterval(context, featureStore);


        if (interval == null || interval.isBlank()) {
            return hold(null, "Interval is null");
        }

        if (context.getActiveTrade() != null){
            return hold( interval,"Active trades exist");
        }

        IntervalProfile profile = INTERVAL_PROFILES.get(interval);
        if (profile == null) {
            return hold(interval, "Unsupported interval: " + interval);
        }

        if (positionSnapshot != null && positionSnapshot.isHasOpenPosition()) {
            return hold(interval, "Open trade managed by listener");
        }

        if (!Boolean.TRUE.equals(context.isAllowLong())) {
            return hold(interval, "Long disabled");
        }

        BigDecimal close = marketData.getClosePrice();
        if (close == null) {
            return hold(interval, "Close price is null");
        }

        if (!isValidAtr(featureStore)) {
            return hold(interval, "ATR invalid");
        }

        if (featureStore.getInterval() != null && !interval.equalsIgnoreCase(featureStore.getInterval())) {
            return hold(interval,
                    "Feature interval mismatch. expected=" + interval + ", actual=" + featureStore.getInterval());
        }

        boolean validLongBias = isValidLongBias(featureStore, close, profile);
        if (!validLongBias) {
            return hold(interval, "No valid " + interval + " TSMOM long entry");
        }

        BigDecimal atr = featureStore.getAtr();
        BigDecimal stopLoss = close.subtract(atr.multiply(profile.stopAtrMultiplier()));
        BigDecimal takeProfit = close.add(atr.multiply(profile.takeProfitAtrMultiplier()));

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(interval)
                .side(SIDE_LONG)
                .reason(interval + " time-series momentum long")
                .positionSize(BigDecimal.ONE)
                .stopLossPrice(stopLoss)
                .takeProfitPrice(takeProfit)
                .entryAdx(featureStore.getAdx())
                .entryAtr(featureStore.getAtr())
                .entryTrendRegime(featureStore.getTrendRegime())
                .build();
    }

    private boolean isValidLongBias(FeatureStore f, BigDecimal close, IntervalProfile profile) {
        return f != null
                && close != null
                && f.getEma20() != null
                && f.getEma50() != null
                && f.getEma200() != null
                && close.compareTo(f.getEma20()) >= 0
                && f.getEma20().compareTo(f.getEma50()) > 0
                && f.getEma50().compareTo(f.getEma200()) > 0
                && positiveOrZero(f.getEma50Slope())
                && positiveOrZero(f.getEma200Slope())
                && f.getAdx() != null
                && f.getAdx().compareTo(profile.minAdx()) >= 0;
    }

    private boolean isValidAtr(FeatureStore f) {
        return f != null
                && f.getAtr() != null
                && f.getAtr().compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean positiveOrZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    private String resolveInterval(StrategyContext context, FeatureStore featureStore) {
        if (context.getInterval() != null && !context.getInterval().isBlank()) {
            return context.getInterval();
        }
        if (featureStore != null && featureStore.getInterval() != null && !featureStore.getInterval().isBlank()) {
            return featureStore.getInterval();
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

    private record IntervalProfile(
            BigDecimal minAdx,
            BigDecimal stopAtrMultiplier,
            BigDecimal takeProfitAtrMultiplier
    ) {
    }
}