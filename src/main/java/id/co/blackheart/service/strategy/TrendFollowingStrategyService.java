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
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrendFollowingStrategyService implements StrategyExecutor {

    public static final String STRATEGY_NAME = "TREND_FOLLOWING";
    public static final String SIDE_LONG = "LONG";
    public static final String SIDE_SHORT = "SHORT";

    private static final String INTERVAL_15M = "15m";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String EXIT_STRUCTURE_TP1_TP2_RUNNER = "TP1_TP2_RUNNER";
    private static final String EXIT_STRUCTURE_RUNNER_ONLY = "RUNNER_ONLY";

    private static final String TARGET_ALL = "ALL";
    private static final String TARGET_SINGLE = "SINGLE";
    private static final String TARGET_RUNNER = "RUNNER";

    /**
     * true  = always use code config
     * false = use DB first, fallback to code
     */
    private static final boolean FORCE_CODE_CONFIG = false;

    private final StrategyConfigRepository strategyConfigRepository;

    private static final Map<String, LocalTrendConfig> CODE_CONFIGS = Map.of(
            "15m", LocalTrendConfig.builder()
                    .minAdx(new BigDecimal("25"))
                    .minEfficiencyRatio(new BigDecimal("0.40"))
                    .minRelativeVolume(new BigDecimal("1.05"))
                    .stopAtrMultiplier(new BigDecimal("1.20"))
                    .takeProfitAtrMultiplier(new BigDecimal("2.00"))
                    .trailingAtrMultiplier(new BigDecimal("1.00"))
                    .allowLong(true)
                    .allowShort(false)
                    .allowBreakoutEntry(false)
                    .allowPullbackEntry(true)
                    .allowBiasEntry(false)
                    .build(),

            "1h", LocalTrendConfig.builder()
                    .minAdx(new BigDecimal("23"))
                    .minEfficiencyRatio(new BigDecimal("0.35"))
                    .minRelativeVolume(new BigDecimal("1.00"))
                    .stopAtrMultiplier(new BigDecimal("1.80"))
                    .takeProfitAtrMultiplier(new BigDecimal("3.00"))
                    .trailingAtrMultiplier(new BigDecimal("1.50"))
                    .allowLong(true)
                    .allowShort(false)
                    .allowBreakoutEntry(false)
                    .allowPullbackEntry(true)
                    .allowBiasEntry(false)
                    .build(),

            "4h", LocalTrendConfig.builder()
                    .minAdx(new BigDecimal("22"))
                    .minEfficiencyRatio(new BigDecimal("0.30"))
                    .minRelativeVolume(new BigDecimal("0.80"))
                    .stopAtrMultiplier(new BigDecimal("2.50"))
                    .takeProfitAtrMultiplier(new BigDecimal("4.00"))
                    .trailingAtrMultiplier(new BigDecimal("2.00"))
                    .allowLong(true)
                    .allowShort(false)
                    .allowBreakoutEntry(false)
                    .allowPullbackEntry(false)
                    .allowBiasEntry(false)
                    .build()
    );

    @Override
    public StrategyDecision execute(StrategyContext context) {
        if (context == null
                || context.getMarketData() == null
                || context.getFeatureStore() == null) {
            return hold(null, "Invalid context");
        }

        String interval = resolveInterval(context);
        MarketData marketData = context.getMarketData();
        FeatureStore featureStore = context.getFeatureStore();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();

        if (interval == null || interval.isBlank()) {
            return hold(null, "Interval is null");
        }

        if (marketData.getClosePrice() == null) {
            return hold(interval, "Close price is null");
        }

        if (featureStore.getInterval() != null
                && !interval.equalsIgnoreCase(featureStore.getInterval())) {
            return hold(interval,
                    "Feature interval mismatch. expected=" + interval + ", actual=" + featureStore.getInterval());
        }

        Optional<LocalTrendConfig> configOpt = resolveConfig(interval, context.getAsset());
        if (configOpt.isEmpty()) {
            return hold(interval, "No config found from DB or code");
        }

        LocalTrendConfig config = configOpt.get();

        boolean effectiveAllowLong =
                Boolean.TRUE.equals(context.isAllowLong()) && config.allowLong();


        if (hasOpenPosition(positionSnapshot)) {
            return manageOpenPosition(context, config, positionSnapshot);
        }

        if (!effectiveAllowLong) {
            return hold(interval, "Long disabled");
        }

        if (INTERVAL_15M.equalsIgnoreCase(interval)) {
            return evaluate15mWith4hBias(context, config);
        }

        return evaluateBaselineLongOnly(context, config);
    }

    private StrategyDecision evaluateBaselineLongOnly(
            StrategyContext context,
            LocalTrendConfig config
    ) {
        String interval = resolveInterval(context);
        FeatureStore featureStore = context.getFeatureStore();
        BigDecimal close = context.getMarketData().getClosePrice();

        boolean bullishRegime = isBullishRegime(featureStore, close);
        boolean strongTrend = hasStrongTrend(featureStore, config);
        boolean acceptableVolume = hasAcceptableVolume(featureStore, config);

        if (!bullishRegime) return hold(interval, "Rejected long: bullishRegime=false");
        if (!strongTrend) return hold(interval, "Rejected long: strongTrend=false");
        if (!acceptableVolume) return hold(interval, "Rejected long: acceptableVolume=false");

        Optional<BigDecimal> atrOpt = getValidAtr(featureStore);
        if (atrOpt.isEmpty()) {
            return hold(interval, "ATR invalid");
        }

        return buildOpenLong(
                context,
                interval,
                "Bullish regime + strong trend",
                featureStore,
                close,
                atrOpt.get(),
                config
        );
    }

    private StrategyDecision evaluate15mWith4hBias(
            StrategyContext context,
            LocalTrendConfig config
    ) {
        FeatureStore entry = context.getFeatureStore();
        MarketData entryMarket = context.getMarketData();
        FeatureStore bias = context.getBiasFeatureStore();
        MarketData biasMarket = context.getBiasMarketData();

        if (bias == null || biasMarket == null) {
            return hold(INTERVAL_15M, "15m strategy requires 4h bias market/feature");
        }

        BigDecimal entryClose = entryMarket.getClosePrice();
        BigDecimal biasClose = biasMarket.getClosePrice();

        boolean bullishBias = isBullishRegime(bias, biasClose) && hasStrongTrend(bias, config);
        boolean bullishMomentum = hasBullishMomentum(entry);
        boolean acceptableVolume = hasAcceptableVolume(entry, config);
        boolean validTrigger = isValid15mLongTrigger(entry, config);
        boolean reclaimedTrend = reclaimsFastTrend(entry, entryClose);
        boolean notOverextended = isNotOverextendedLong15m(entry, entryClose);

        if (!bullishBias) return hold(INTERVAL_15M, "Rejected 15m long: bullishBias=false");
        if (!bullishMomentum) return hold(INTERVAL_15M, "Rejected 15m long: bullishMomentum=false");
        if (!acceptableVolume) return hold(INTERVAL_15M, "Rejected 15m long: acceptableVolume=false");
        if (!validTrigger) return hold(INTERVAL_15M, "Rejected 15m long: validTrigger=false");
        if (!reclaimedTrend) return hold(INTERVAL_15M, "Rejected 15m long: reclaimedTrend=false");
        if (!notOverextended) return hold(INTERVAL_15M, "Rejected 15m long: notOverextended=false");

        Optional<BigDecimal> atrOpt = getValidAtr(entry);
        if (atrOpt.isEmpty()) {
            return hold(INTERVAL_15M, "ATR invalid");
        }

        return buildOpenLong(
                context,
                INTERVAL_15M,
                "4h bullish bias + 15m pullback continuation",
                entry,
                entryClose,
                atrOpt.get(),
                config
        );
    }

    private StrategyDecision buildOpenLong(
            StrategyContext context,
            String interval,
            String reason,
            FeatureStore featureStore,
            BigDecimal closePrice,
            BigDecimal atr,
            LocalTrendConfig config
    ) {
        BigDecimal stopLoss = closePrice.subtract(atr.multiply(config.stopAtrMultiplier()));
        BigDecimal tp1 = closePrice.add(atr.multiply(config.takeProfitAtrMultiplier()));
        BigDecimal tp2 = closePrice.add(atr.multiply(config.takeProfitAtrMultiplier()).multiply(new BigDecimal("1.50")));

        String exitStructure = resolveExitStructure(interval);

        BigDecimal positionSize = calculatePositionSize(context, SIDE_LONG);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(interval)
                .side(SIDE_LONG)
                .reason(reason)
                .positionSize(positionSize)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(resolveTakeProfitPrice1(exitStructure, tp1))
                .takeProfitPrice2(resolveTakeProfitPrice2(exitStructure, tp2))
                .takeProfitPrice3(null)
                .exitStructure(exitStructure)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(featureStore.getAdx())
                .entryAtr(featureStore.getAtr())
                .entryRsi(featureStore.getRsi())
                .entryTrendRegime(featureStore.getTrendRegime())
                .build();
    }


    private BigDecimal calculatePositionSize(StrategyContext context, String side) {
        if (context == null || side == null || side.isBlank()) {
            return BigDecimal.ZERO;
        }

        BigDecimal riskPerTradePct = context.getRiskPerTradePct();
        if (riskPerTradePct == null || riskPerTradePct.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal baseAmount = "SHORT".equalsIgnoreCase(side)
                ? context.getAssetBalance()
                : context.getCashBalance();

        if (baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return baseAmount
                .multiply(riskPerTradePct)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private StrategyDecision manageOpenPosition(
            StrategyContext context,
            LocalTrendConfig config,
            PositionSnapshot positionSnapshot
    ) {
        if (positionSnapshot == null || !positionSnapshot.isHasOpenPosition()) {
            return hold(resolveInterval(context), "No open position");
        }

        if (!SIDE_LONG.equalsIgnoreCase(positionSnapshot.getSide())) {
            return hold(resolveInterval(context), "Only long management supported");
        }

        BigDecimal closePrice = context.getMarketData().getClosePrice();
        BigDecimal entryPrice = positionSnapshot.getEntryPrice();
        BigDecimal atr = getValidAtr(context.getFeatureStore()).orElse(null);

        if (closePrice == null || entryPrice == null || atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(resolveInterval(context), "Management inputs invalid");
        }

        BigDecimal move = closePrice.subtract(entryPrice);
        BigDecimal breakEvenTrigger = atr.multiply(BigDecimal.ONE);
        BigDecimal trailTrigger = atr.multiply(new BigDecimal("2.0"));

        if (move.compareTo(breakEvenTrigger) < 0) {
            return hold(resolveInterval(context), "Open trade managed by listener");
        }

        BigDecimal currentStop = positionSnapshot.getCurrentStopLossPrice();
        BigDecimal breakEvenStop = entryPrice;
        BigDecimal atrTrail = closePrice.subtract(atr.multiply(config.trailingAtrMultiplier()));

        BigDecimal updatedStop = maxNonNull(currentStop, breakEvenStop);
        if (move.compareTo(trailTrigger) >= 0) {
            updatedStop = maxNonNull(updatedStop, atrTrail);
        }

        String role = positionSnapshot.getPositionRole();
        BigDecimal updatedTp1 = null;
        BigDecimal updatedTp2 = null;

        if ("SINGLE".equalsIgnoreCase(role)) {
            updatedTp1 = closePrice.add(atr.multiply(new BigDecimal("1.00")));
        } else if ("TP1".equalsIgnoreCase(role)) {
            updatedTp1 = closePrice.add(atr.multiply(new BigDecimal("1.00")));
        } else if ("TP2".equalsIgnoreCase(role)) {
            updatedTp2 = closePrice.add(atr.multiply(new BigDecimal("1.25")));
        } else if ("RUNNER".equalsIgnoreCase(role)) {
            updatedTp1 = null;
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(resolveInterval(context))
                .side(SIDE_LONG)
                .reason("Dynamic long position management update")
                .stopLossPrice(updatedStop)
                .trailingStopPrice(updatedStop)
                .takeProfitPrice1(updatedTp1)
                .takeProfitPrice2(updatedTp2)
                .takeProfitPrice3(null)
                .targetPositionRole(resolveTargetRole(role))
                .build();
    }

    private Optional<LocalTrendConfig> resolveConfig(String interval, String symbol) {
        if (FORCE_CODE_CONFIG) {
            return Optional.ofNullable(CODE_CONFIGS.get(interval))
                    .map(c -> c.withSource("CODE"));
        }

        Optional<LocalTrendConfig> dbConfig = findDbConfig(interval, symbol);
        if (dbConfig.isPresent()) {
            return dbConfig;
        }

        return Optional.ofNullable(CODE_CONFIGS.get(interval))
                .map(c -> c.withSource("CODE"));
    }

    private Optional<LocalTrendConfig> findDbConfig(String interval, String symbol) {
        Optional<TrendFollowingConfigProjection> configOpt;

        if (symbol != null && !symbol.isBlank()) {
            configOpt = strategyConfigRepository.findActiveBySymbol(STRATEGY_NAME, interval, symbol);
            if (configOpt.isPresent()) {
                return Optional.of(mapProjection(configOpt.get(), "DB_SYMBOL"));
            }
        }

        configOpt = strategyConfigRepository.findActiveDefault(STRATEGY_NAME, interval);
        return configOpt.map(cfg -> mapProjection(cfg, "DB_DEFAULT"));
    }

    private LocalTrendConfig mapProjection(TrendFollowingConfigProjection p, String source) {
        return LocalTrendConfig.builder()
                .source(source)
                .strategyConfigId(p.getStrategyConfigId())
                .minAdx(p.getMinAdx())
                .minEfficiencyRatio(p.getMinEfficiencyRatio())
                .minRelativeVolume(p.getMinRelativeVolume())
                .stopAtrMultiplier(p.getStopAtrMultiplier())
                .takeProfitAtrMultiplier(p.getTakeProfitAtrMultiplier())
                .trailingAtrMultiplier(p.getTrailingAtrMultiplier())
                .allowLong(Boolean.TRUE.equals(p.getAllowLong()))
                .allowShort(Boolean.TRUE.equals(p.getAllowShort()))
                .allowBreakoutEntry(Boolean.TRUE.equals(p.getAllowBreakoutEntry()))
                .allowPullbackEntry(Boolean.TRUE.equals(p.getAllowPullbackEntry()))
                .allowBiasEntry(Boolean.TRUE.equals(p.getAllowBiasEntry()))
                .build();
    }

    private boolean isBullishRegime(FeatureStore f, BigDecimal closePrice) {
        return f != null
                && "BULL".equalsIgnoreCase(f.getTrendRegime())
                && f.getEma20() != null
                && f.getEma50() != null
                && f.getEma200() != null
                && closePrice != null
                && f.getEma20().compareTo(f.getEma50()) > 0
                && f.getEma50().compareTo(f.getEma200()) > 0
                && closePrice.compareTo(f.getEma20()) >= 0
                && positiveOrZero(f.getEma50Slope())
                && positiveOrZero(f.getEma200Slope());
    }

    private boolean hasStrongTrend(FeatureStore f, LocalTrendConfig config) {
        return f != null
                && f.getAdx() != null
                && config.minAdx() != null
                && f.getAdx().compareTo(config.minAdx()) >= 0
                && f.getEfficiencyRatio20() != null
                && config.minEfficiencyRatio() != null
                && f.getEfficiencyRatio20().compareTo(config.minEfficiencyRatio()) >= 0;
    }

    private boolean hasBullishMomentum(FeatureStore f) {
        return f != null
                && f.getPlusDI() != null
                && f.getMinusDI() != null
                && f.getPlusDI().compareTo(f.getMinusDI()) > 0
                && f.getMacd() != null
                && f.getMacdSignal() != null
                && f.getMacd().compareTo(f.getMacdSignal()) > 0
                && f.getMacdHistogram() != null
                && f.getMacdHistogram().compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean hasAcceptableVolume(FeatureStore f, LocalTrendConfig config) {
        return f != null
                && (f.getRelativeVolume20() == null
                || (config.minRelativeVolume() != null
                && f.getRelativeVolume20().compareTo(config.minRelativeVolume()) >= 0));
    }

    private boolean isValid15mLongTrigger(FeatureStore f, LocalTrendConfig config) {
        return f != null
                && config.allowPullbackEntry()
                && Boolean.TRUE.equals(f.getIsBullishPullback());
    }

    private boolean reclaimsFastTrend(FeatureStore f, BigDecimal closePrice) {
        return f != null
                && closePrice != null
                && f.getEma20() != null
                && f.getEma50() != null
                && closePrice.compareTo(f.getEma20()) >= 0
                && closePrice.compareTo(f.getEma50()) >= 0;
    }

    private boolean isNotOverextendedLong15m(FeatureStore f, BigDecimal closePrice) {
        if (f == null || closePrice == null || f.getEma20() == null || f.getAtr() == null) {
            return true;
        }
        BigDecimal maxAllowed = f.getEma20().add(f.getAtr().multiply(new BigDecimal("0.80")));
        return closePrice.compareTo(maxAllowed) <= 0;
    }

    private Optional<BigDecimal> getValidAtr(FeatureStore featureStore) {
        if (featureStore == null
                || featureStore.getAtr() == null
                || featureStore.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(featureStore.getAtr());
    }

    private boolean positiveOrZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    private boolean hasOpenPosition(PositionSnapshot positionSnapshot) {
        return positionSnapshot != null && positionSnapshot.isHasOpenPosition();
    }

    private String resolveInterval(StrategyContext context) {
        if (context == null) return null;
        if (context.getInterval() != null && !context.getInterval().isBlank()) {
            return context.getInterval();
        }
        if (context.getFeatureStore() != null) {
            return context.getFeatureStore().getInterval();
        }
        return null;
    }

    private String resolveExitStructure(String interval) {
        if (interval == null) {
            return EXIT_STRUCTURE_SINGLE;
        }

        return switch (interval.toLowerCase()) {
            case "15m", "1h" -> EXIT_STRUCTURE_TP1_RUNNER;
            case "4h" -> EXIT_STRUCTURE_TP1_TP2_RUNNER;
            case "1d" -> EXIT_STRUCTURE_RUNNER_ONLY;
            default -> EXIT_STRUCTURE_SINGLE;
        };
    }

    private BigDecimal resolveTakeProfitPrice1(String exitStructure, BigDecimal tp1) {
        return switch (exitStructure) {
            case EXIT_STRUCTURE_SINGLE, EXIT_STRUCTURE_TP1_RUNNER, EXIT_STRUCTURE_TP1_TP2_RUNNER -> tp1;
            case EXIT_STRUCTURE_RUNNER_ONLY -> null;
            default -> tp1;
        };
    }

    private BigDecimal resolveTakeProfitPrice2(String exitStructure, BigDecimal tp2) {
        return EXIT_STRUCTURE_TP1_TP2_RUNNER.equalsIgnoreCase(exitStructure) ? tp2 : null;
    }

    private String resolveTargetRole(String positionRole) {
        if (positionRole == null || positionRole.isBlank()) {
            return TARGET_ALL;
        }

        if (TARGET_SINGLE.equalsIgnoreCase(positionRole)) {
            return TARGET_SINGLE;
        }

        if (TARGET_RUNNER.equalsIgnoreCase(positionRole)) {
            return TARGET_RUNNER;
        }

        return positionRole;
    }

    private BigDecimal maxNonNull(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.max(b);
    }

    private StrategyDecision hold(String interval, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(interval)
                .reason(reason)
                .build();
    }

    @lombok.Builder
    private record LocalTrendConfig(
            String source,
            UUID strategyConfigId,
            BigDecimal minAdx,
            BigDecimal minEfficiencyRatio,
            BigDecimal minRelativeVolume,
            BigDecimal stopAtrMultiplier,
            BigDecimal takeProfitAtrMultiplier,
            BigDecimal trailingAtrMultiplier,
            boolean allowLong,
            boolean allowShort,
            boolean allowBreakoutEntry,
            boolean allowPullbackEntry,
            boolean allowBiasEntry
    ) {
        private LocalTrendConfig withSource(String newSource) {
            return new LocalTrendConfig(
                    newSource,
                    strategyConfigId,
                    minAdx,
                    minEfficiencyRatio,
                    minRelativeVolume,
                    stopAtrMultiplier,
                    takeProfitAtrMultiplier,
                    trailingAtrMultiplier,
                    allowLong,
                    allowShort,
                    allowBreakoutEntry,
                    allowPullbackEntry,
                    allowBiasEntry
            );
        }
    }
}