package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimeSeriesMomentumService implements StrategyExecutor {

    public static final String STRATEGY_NAME = "TSMOM";
    public static final String SIDE_LONG = "LONG";

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String EXIT_STRUCTURE_TP1_TP2_RUNNER = "TP1_TP2_RUNNER";
    private static final String TARGET_ALL = "ALL";
    private static final String TARGET_SINGLE = "SINGLE";
    private static final String TARGET_RUNNER = "RUNNER";

    private static final Map<String, IntervalProfile> INTERVAL_PROFILES = Map.of(
            "15m", new IntervalProfile(
                    new BigDecimal("25"),
                    new BigDecimal("1.2"),
                    new BigDecimal("2.0"),
                    new BigDecimal("3.0")
            ),
            "1h", new IntervalProfile(
                    new BigDecimal("23"),
                    new BigDecimal("1.8"),
                    new BigDecimal("3.0"),
                    new BigDecimal("4.0")
            ),
            "4h", new IntervalProfile(
                    new BigDecimal("22"),
                    new BigDecimal("2.5"),
                    new BigDecimal("4.0"),
                    new BigDecimal("6.0")
            ),
            "1d", new IntervalProfile(
                    new BigDecimal("20"),
                    new BigDecimal("3.0"),
                    new BigDecimal("5.0"),
                    new BigDecimal("8.0")
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

        IntervalProfile profile = INTERVAL_PROFILES.get(interval);
        if (profile == null) {
            return hold(interval, "Unsupported interval: " + interval);
        }

        if (positionSnapshot != null && positionSnapshot.isHasOpenPosition()) {
            return manageOpenPosition(context, featureStore, marketData, positionSnapshot, profile, interval);
        }

        if (context.getCurrentOpenTradeCount() != null
                && context.getMaxOpenPositions() != null
                && context.getCurrentOpenTradeCount() >= context.getMaxOpenPositions()) {
            return hold(interval, "Max open parent trades reached");
        }

        if (!context.isAllowLong()) {
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

        if (!isValidLongBias(featureStore, close, profile)) {
            return hold(interval, "No valid " + interval + " TSMOM long entry");
        }

        BigDecimal atr = featureStore.getAtr();
        BigDecimal stopLoss = close.subtract(atr.multiply(profile.stopAtrMultiplier()));
        BigDecimal tp1 = close.add(atr.multiply(profile.takeProfit1AtrMultiplier()));
        BigDecimal tp2 = close.add(atr.multiply(profile.takeProfit2AtrMultiplier()));

        BigDecimal positionSize = calculatePositionSize(context, SIDE_LONG);
        if (positionSize.compareTo(ZERO) <= 0) {
            return hold(interval, "Calculated position size is zero or invalid");
        }

        String exitStructure = resolveExitStructure(interval);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(interval)
                .side(SIDE_LONG)
                .reason(interval + " time-series momentum long")
                .positionSize(positionSize)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(EXIT_STRUCTURE_TP1_TP2_RUNNER.equals(exitStructure) ? tp2 : null)
                .exitStructure(exitStructure)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(featureStore.getAdx())
                .entryAtr(featureStore.getAtr())
                .entryTrendRegime(featureStore.getTrendRegime())
                .build();
    }


    private StrategyDecision manageOpenPosition(
            StrategyContext context,
            FeatureStore featureStore,
            MarketData marketData,
            PositionSnapshot positionSnapshot,
            IntervalProfile profile,
            String interval
    ) {
        if (!SIDE_LONG.equalsIgnoreCase(positionSnapshot.getSide())) {
            return hold(interval, "Open non-long position");
        }

        BigDecimal close = marketData.getClosePrice();
        BigDecimal atr = featureStore.getAtr();
        BigDecimal entryPrice = positionSnapshot.getEntryPrice();
        BigDecimal currentStop = positionSnapshot.getCurrentStopLossPrice();
        BigDecimal currentTp = positionSnapshot.getTakeProfitPrice();

        if (close == null || atr == null || entryPrice == null || atr.compareTo(ZERO) <= 0) {
            return hold(interval, "Open trade but management inputs invalid");
        }

        BigDecimal move = close.subtract(entryPrice);
        BigDecimal breakEvenTrigger = atr.multiply(new BigDecimal("1.0"));
        BigDecimal trailTrigger = atr.multiply(new BigDecimal("2.0"));

        BigDecimal newStop = currentStop;
        BigDecimal newTp = currentTp;
        boolean changed = false;

        if (move.compareTo(breakEvenTrigger) >= 0) {
            BigDecimal breakEvenStop = entryPrice;
            if (newStop == null || breakEvenStop.compareTo(newStop) > 0) {
                newStop = breakEvenStop;
                changed = true;
            }
        }

        if (move.compareTo(trailTrigger) >= 0) {
            BigDecimal atrTrail = close.subtract(atr.multiply(new BigDecimal("1.5")));
            if (newStop == null || atrTrail.compareTo(newStop) > 0) {
                newStop = atrTrail;
                changed = true;
            }
        }

        if (currentTp != null && close.compareTo(currentTp) >= 0 && trendStillStrong(featureStore, close, profile)) {
            BigDecimal extendedTp = close.add(atr.multiply(new BigDecimal("1.5")));
            if (extendedTp.compareTo(currentTp) > 0) {
                newTp = extendedTp;
                changed = true;
            }
        }

        if (!changed) {
            return hold(interval, "Open trade managed by strategy");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(interval)
                .side(SIDE_LONG)
                .reason(interval + " dynamic management update")
                .stopLossPrice(newStop)
                .trailingStopPrice(newStop)
                .takeProfitPrice1(newTp)
                .targetPositionRole(resolveManagementTarget(positionSnapshot))
                .build();
    }

    private String resolveManagementTarget(PositionSnapshot snapshot) {
        if (snapshot == null || snapshot.getPositionRole() == null) {
            return TARGET_ALL;
        }

        if (TARGET_SINGLE.equalsIgnoreCase(snapshot.getPositionRole())) {
            return TARGET_SINGLE;
        }

        if (TARGET_RUNNER.equalsIgnoreCase(snapshot.getPositionRole())) {
            return TARGET_RUNNER;
        }

        return snapshot.getPositionRole();
    }

    private String resolveExitStructure(String interval) {
        return switch (interval.toLowerCase()) {
            case "15m", "1h" -> EXIT_STRUCTURE_TP1_RUNNER;
            case "4h", "1d" -> EXIT_STRUCTURE_TP1_TP2_RUNNER;
            default -> EXIT_STRUCTURE_SINGLE;
        };
    }

    private BigDecimal calculatePositionSize(StrategyContext context, String side) {
        if (context == null || side == null || side.isBlank()) {
            return ZERO;
        }

        BigDecimal riskPerTradePct = context.getRiskPerTradePct();
        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        BigDecimal baseAmount = "SHORT".equalsIgnoreCase(side)
                ? context.getAssetBalance()
                : context.getCashBalance();

        if (baseAmount == null || baseAmount.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        return baseAmount.multiply(riskPerTradePct).setScale(8, RoundingMode.HALF_UP);
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

    private boolean trendStillStrong(FeatureStore f, BigDecimal close, IntervalProfile profile) {
        return f != null
                && close != null
                && f.getEma20() != null
                && f.getEma50() != null
                && f.getEma200() != null
                && f.getAdx() != null
                && close.compareTo(f.getEma20()) >= 0
                && f.getEma20().compareTo(f.getEma50()) > 0
                && f.getEma50().compareTo(f.getEma200()) > 0
                && positiveOrZero(f.getEma50Slope())
                && positiveOrZero(f.getEma200Slope())
                && f.getAdx().compareTo(profile.minAdx()) >= 0;
    }

    private boolean isValidAtr(FeatureStore f) {
        return f != null
                && f.getAtr() != null
                && f.getAtr().compareTo(ZERO) > 0;
    }

    private boolean positiveOrZero(BigDecimal value) {
        return value != null && value.compareTo(ZERO) >= 0;
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
            BigDecimal takeProfit1AtrMultiplier,
            BigDecimal takeProfit2AtrMultiplier
    ) {
    }
}