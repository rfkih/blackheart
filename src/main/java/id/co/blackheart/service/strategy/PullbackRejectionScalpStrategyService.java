package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.MarketQualitySnapshot;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.RiskSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.strategy.VolatilitySnapshot;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class PullbackRejectionScalpStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;

    private static final String STRATEGY_CODE = "PULLBACK_REJECTION_SCALP";
    private static final String STRATEGY_NAME = "Pullback Rejection Scalp Strategy";
    private static final String STRATEGY_VERSION = "v1";

    private static final String SIDE_LONG = "LONG";

    private static final String SIGNAL_TYPE = "TREND_PULLBACK";
    private static final String SETUP_TYPE_ENTRY = "BULL_PULLBACK_REJECTION";
    private static final String SETUP_TYPE_MANAGEMENT = "PULLBACK_SCALP_STOP_REDUCTION";

    private static final String EXIT_STRUCTURE = "SINGLE";
    private static final String TARGET_ALL = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    // ===== Trend / bias filters =====
    private static final BigDecimal ADX_MIN = new BigDecimal("18");
    private static final BigDecimal ADX_MAX = new BigDecimal("40");
    private static final BigDecimal TREND_SCORE_MIN = new BigDecimal("0.35");
    private static final BigDecimal MAX_JUMP_RISK = new BigDecimal("0.85");

    // ===== Pullback / reclaim logic =====
    // previous close should be at or below EMA20 zone
    private static final BigDecimal PREV_CLOSE_MAX_EMA20 = new BigDecimal("1.002");
    // current close should reclaim above EMA20
    private static final BigDecimal CURRENT_CLOSE_MIN_EMA20 = new BigDecimal("1.000");
    // current close should still be reasonably near EMA20, not too extended
    private static final BigDecimal CURRENT_CLOSE_MAX_EMA20 = new BigDecimal("1.006");
    // current close should remain above EMA50 structure support
    private static final BigDecimal CURRENT_CLOSE_MIN_EMA50 = new BigDecimal("0.998");

    // ===== Entry quality =====
    private static final BigDecimal RELATIVE_VOLUME_MIN = new BigDecimal("0.95");
    private static final BigDecimal BODY_RATIO_MIN = new BigDecimal("0.45");
    private static final BigDecimal RSI_MIN = new BigDecimal("42");
    private static final BigDecimal RSI_MAX = new BigDecimal("58");

    // ===== Risk / exit =====
    private static final BigDecimal STOP_ATR_MULTIPLIER = new BigDecimal("0.65");
    private static final BigDecimal TAKE_PROFIT_R = new BigDecimal("1.30");
    private static final BigDecimal STOP_REDUCE_TRIGGER_R = new BigDecimal("0.70");
    private static final BigDecimal SOFT_LOCK_R = new BigDecimal("0.08");
    private static final BigDecimal MAX_RISK_PCT_OF_PRICE = new BigDecimal("0.010");

    private static final Integer MAX_HOLDING_BARS = 4;
    private static final Integer COOLDOWN_BARS = 1;

    // ===== Session filter =====
    private static final Set<Integer> BLOCKED_HOURS_UTC = Set.of(1, 2, 3, 4);

    @Override
    public StrategyRequirements getRequirements() {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval("1h")
                .requireRegimeSnapshot(true)
                .requireVolatilitySnapshot(true)
                .requireRiskSnapshot(true)
                .requireMarketQualitySnapshot(true)
                .requirePreviousFeatureStore(true)
                .build();
    }

    @Override
    public StrategyDecision execute(EnrichedStrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(context, "Missing context / market data / feature store");
        }

        if (!context.isLongAllowed()) {
            return hold(context, "Long not allowed");
        }

        MarketData marketData = context.getMarketData();
        FeatureStore feature = context.getFeatureStore();
        FeatureStore prev = context.getPreviousFeatureStore();

        BigDecimal close = safe(marketData.getClosePrice());
        if (close.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid close price");
        }

        if (context.hasTradablePosition()) {
            return manageOpenPosition(context);
        }

        if (isBlockedBySession(marketData)) {
            return hold(context, "Blocked trading session");
        }

        if (isMarketVetoed(context)) {
            return veto(context, "Market vetoed by quality / volatility / controls");
        }

        if (!isBullishBias(context)) {
            return hold(context, "1h bullish bias not confirmed");
        }

        if (!isBullishPullbackStructure(feature, context)) {
            return hold(context, "5m bullish pullback structure not confirmed");
        }

        if (!passesPullbackReclaim(context, marketData, feature, prev)) {
            return hold(context, "No valid pullback reclaim rejection");
        }

        if (!passesEntryQuality(feature)) {
            return hold(context, "Entry quality filters not passed");
        }

        BigDecimal atr = resolveAtr(feature);
        BigDecimal stopLoss = buildLongStop(feature, close, atr);
        BigDecimal risk = close.subtract(stopLoss);

        if (!isRiskUsable(close, risk)) {
            return hold(context, "Risk too wide or invalid");
        }

        BigDecimal takeProfit = close.add(risk.multiply(TAKE_PROFIT_R));
        BigDecimal signalScore = calculateSignalScore(context);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (!passesScore(signalScore, confidenceScore)) {
            return hold(context, "Signal score below threshold");
        }

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_LONG);
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated notional is zero");
        }

        log.info(
                "PullbackRejectionScalp ENTRY | time={} close={} ema20={} ema50={} stop={} tp={} signal={} confidence={}",
                marketData.getEndTime(),
                close,
                feature.getEma20(),
                feature.getEma50(),
                stopLoss,
                takeProfit,
                signalScore,
                confidenceScore
        );

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE)
                .setupType(SETUP_TYPE_ENTRY)
                .side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .marketStateLabel("BULLISH_PULLBACK_SCALP")
                .reason("Pullback rejection scalp long: bullish bias + EMA20 reclaim near support")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .volatilityScore(resolveVolatilityScore(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .liquidityScore(resolveLiquidityScore(context))
                .accountStrategyId(context.getAccountStrategy() != null ? context.getAccountStrategy().getAccountStrategyId() : null)
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss)
                .takeProfitPrice1(takeProfit)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .maxHoldingBars(MAX_HOLDING_BARS)
                .cooldownBars(COOLDOWN_BARS)
                .decisionTime(LocalDateTime.now())
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .tags(List.of("ENTRY", "SCALP", "LONG", "PULLBACK_REJECTION", "5M"))
                .diagnostics(buildEntryDiagnostics(context, feature, close, stopLoss, takeProfit, signalScore, confidenceScore))
                .build();
    }

    // =========================================================================
    // Entry Logic
    // =========================================================================

    private boolean isBullishBias(EnrichedStrategyContext context) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        if (biasFeature == null) {
            return true;
        }

        return has(biasFeature.getEma50())
                && has(biasFeature.getEma200())
                && biasFeature.getEma50().compareTo(biasFeature.getEma200()) > 0;
    }

    private boolean isBullishPullbackStructure(FeatureStore feature, EnrichedStrategyContext context) {
        boolean trendScoreOk = context.getRegimeSnapshot() == null
                || context.getRegimeSnapshot().getTrendScore() == null
                || resolveRegimeScore(context).compareTo(TREND_SCORE_MIN) >= 0;

        return has(feature.getEma20())
                && has(feature.getEma50())
                && feature.getEma20().compareTo(feature.getEma50()) > 0
                && (!has(feature.getAdx()) || inRange(feature.getAdx(), ADX_MIN, ADX_MAX))
                && (!has(feature.getMacdHistogram()) || feature.getMacdHistogram().compareTo(ZERO) > 0)
                && (!has(feature.getEma50Slope()) || feature.getEma50Slope().compareTo(ZERO) > 0)
                && trendScoreOk;
    }

    private boolean passesPullbackReclaim(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            FeatureStore prev
    ) {
        if (feature == null || prev == null) {
            return false;
        }

        if (!has(feature.getEma20()) || !has(feature.getEma50()) || !has(prev.getPrice())) {
            return false;
        }

        BigDecimal ema20 = feature.getEma20();
        BigDecimal ema50 = feature.getEma50();
        BigDecimal prevClose = safe(prev.getPrice());
        BigDecimal close = safe(marketData.getClosePrice());

        boolean previousAtPullbackZone = prevClose.compareTo(ema20.multiply(PREV_CLOSE_MAX_EMA20)) <= 0;
        boolean currentReclaimedEma20 = close.compareTo(ema20.multiply(CURRENT_CLOSE_MIN_EMA20)) >= 0;
        boolean currentNotOverextended = close.compareTo(ema20.multiply(CURRENT_CLOSE_MAX_EMA20)) <= 0;
        boolean currentAboveEma50Support = close.compareTo(ema50.multiply(CURRENT_CLOSE_MIN_EMA50)) >= 0;
        boolean currentAbovePreviousClose = close.compareTo(prevClose) > 0;

        return previousAtPullbackZone
                && currentReclaimedEma20
                && currentNotOverextended
                && currentAboveEma50Support
                && currentAbovePreviousClose;
    }

    private boolean passesEntryQuality(FeatureStore feature) {
        boolean volumeOk = !has(feature.getRelativeVolume20())
                || feature.getRelativeVolume20().compareTo(RELATIVE_VOLUME_MIN) >= 0;

        boolean bodyOk = !has(feature.getBodyToRangeRatio())
                || feature.getBodyToRangeRatio().compareTo(BODY_RATIO_MIN) >= 0;

        boolean rsiOk = !has(feature.getRsi())
                || inRange(feature.getRsi(), RSI_MIN, RSI_MAX);

        return volumeOk && bodyOk && rsiOk;
    }

    private BigDecimal buildLongStop(FeatureStore feature, BigDecimal close, BigDecimal atr) {
        BigDecimal atrStop = close.subtract(atr.multiply(STOP_ATR_MULTIPLIER));

        if (feature != null && has(feature.getEma50())) {
            BigDecimal structureStop = feature.getEma50().subtract(atr.multiply(new BigDecimal("0.20")));
            return atrStop.max(structureStop);
        }

        return atrStop;
    }

    // =========================================================================
    // Position Management
    // =========================================================================

    private StrategyDecision manageOpenPosition(EnrichedStrategyContext context) {
        PositionSnapshot snap = context.getPositionSnapshot();
        MarketData marketData = context.getMarketData();

        if (snap == null || snap.getEntryPrice() == null || snap.getCurrentStopLossPrice() == null || snap.getSide() == null) {
            return hold(context, "Incomplete position snapshot");
        }

        if (!SIDE_LONG.equalsIgnoreCase(snap.getSide())) {
            return hold(context, "PullbackRejectionScalp manages long only");
        }

        BigDecimal entry = safe(snap.getEntryPrice());
        BigDecimal currentStop = safe(snap.getCurrentStopLossPrice());
        BigDecimal close = safe(marketData.getClosePrice());

        BigDecimal initialRisk = entry.subtract(currentStop);
        if (initialRisk.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid long risk");
        }

        BigDecimal progress = close.subtract(entry);

        if (progress.compareTo(initialRisk.multiply(STOP_REDUCE_TRIGGER_R)) >= 0
                && currentStop.compareTo(entry) < 0) {

            BigDecimal newStop = entry.add(initialRisk.multiply(SOFT_LOCK_R));

            return StrategyDecision.builder()
                    .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                    .strategyCode(STRATEGY_CODE)
                    .strategyName(STRATEGY_NAME)
                    .strategyVersion(STRATEGY_VERSION)
                    .strategyInterval(context.getInterval())
                    .signalType("POSITION_MANAGEMENT")
                    .setupType(SETUP_TYPE_MANAGEMENT)
                    .side(SIDE_LONG)
                    .reason("Reduce pullback scalp risk after favorable progress")
                    .stopLossPrice(newStop)
                    .takeProfitPrice1(snap.getTakeProfitPrice())
                    .targetPositionRole(TARGET_ALL)
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("MANAGEMENT", "SCALP", "LONG"))
                    .diagnostics(Map.of(
                            "entryPrice", entry,
                            "currentStop", currentStop,
                            "newStop", newStop,
                            "close", close
                    ))
                    .build();
        }

        return hold(context, "Open long pullback scalp still valid");
    }

    // =========================================================================
    // Scoring
    // =========================================================================

    private BigDecimal calculateSignalScore(EnrichedStrategyContext context) {
        FeatureStore f = context.getFeatureStore();
        BigDecimal score = new BigDecimal("0.48");

        if (has(f.getRelativeVolume20()) && f.getRelativeVolume20().compareTo(new BigDecimal("1.05")) >= 0) {
            score = score.add(new BigDecimal("0.08"));
        }

        if (has(f.getBodyToRangeRatio()) && f.getBodyToRangeRatio().compareTo(new BigDecimal("0.50")) >= 0) {
            score = score.add(new BigDecimal("0.08"));
        }

        if (has(f.getMacdHistogram()) && f.getMacdHistogram().compareTo(ZERO) > 0) {
            score = score.add(new BigDecimal("0.08"));
        }

        if (has(f.getAdx()) && inRange(f.getAdx(), new BigDecimal("18"), new BigDecimal("32"))) {
            score = score.add(new BigDecimal("0.08"));
        }

        if (has(f.getPlusDI()) && has(f.getMinusDI()) && f.getPlusDI().compareTo(f.getMinusDI()) > 0) {
            score = score.add(new BigDecimal("0.06"));
        }

        if (has(f.getEma50Slope()) && f.getEma50Slope().compareTo(ZERO) > 0) {
            score = score.add(new BigDecimal("0.05"));
        }

        if (has(f.getRsi()) && inRange(f.getRsi(), new BigDecimal("44"), new BigDecimal("54"))) {
            score = score.add(new BigDecimal("0.05"));
        }

        return cap(score);
    }

    private BigDecimal calculateConfidenceScore(EnrichedStrategyContext context, BigDecimal signalScore) {
        BigDecimal confidence = safe(signalScore);
        confidence = confidence.add(resolveRegimeScore(context).multiply(new BigDecimal("0.12")));
        confidence = confidence.add(resolveLiquidityScore(context).multiply(new BigDecimal("0.08")));
        confidence = confidence.subtract(resolveJumpRisk(context).multiply(new BigDecimal("0.10")));
        return cap(confidence);
    }

    private boolean passesScore(BigDecimal signalScore, BigDecimal confidenceScore) {
        return signalScore.compareTo(new BigDecimal("0.55")) >= 0
                && confidenceScore.compareTo(new BigDecimal("0.55")) >= 0;
    }

    // =========================================================================
    // Veto / Hold / Diagnostics
    // =========================================================================

    private boolean isMarketVetoed(EnrichedStrategyContext context) {
        if (!isTradable(context)) {
            return true;
        }

        if (resolveJumpRisk(context).compareTo(MAX_JUMP_RISK) > 0) {
            return true;
        }

        if (context.getCurrentOpenTradeCount() != null
                && context.getMaxOpenPositions() != null
                && context.getCurrentOpenTradeCount() >= context.getMaxOpenPositions()) {
            return true;
        }

        return false;
    }

    private boolean isTradable(EnrichedStrategyContext context) {
        MarketQualitySnapshot quality = context.getMarketQualitySnapshot();
        return quality == null || !Boolean.FALSE.equals(quality.getTradable());
    }

    private boolean isBlockedBySession(MarketData marketData) {
        return marketData != null
                && marketData.getEndTime() != null
                && BLOCKED_HOURS_UTC.contains(marketData.getEndTime().getHour());
    }

    private BigDecimal resolveAtr(FeatureStore feature) {
        return feature != null && has(feature.getAtr()) ? feature.getAtr() : ONE;
    }

    private BigDecimal resolveRegimeScore(EnrichedStrategyContext context) {
        RegimeSnapshot regime = context != null ? context.getRegimeSnapshot() : null;
        return regime != null && regime.getTrendScore() != null ? regime.getTrendScore() : ZERO;
    }

    private BigDecimal resolveRiskMultiplier(EnrichedStrategyContext context) {
        RiskSnapshot risk = context != null ? context.getRiskSnapshot() : null;
        return risk != null && risk.getRiskMultiplier() != null ? risk.getRiskMultiplier() : ONE;
    }

    private BigDecimal resolveJumpRisk(EnrichedStrategyContext context) {
        VolatilitySnapshot vol = context != null ? context.getVolatilitySnapshot() : null;
        return vol != null && vol.getJumpRiskScore() != null ? vol.getJumpRiskScore() : ZERO;
    }

    private BigDecimal resolveVolatilityScore(EnrichedStrategyContext context) {
        BigDecimal jumpRisk = resolveJumpRisk(context);
        return ONE.subtract(jumpRisk).max(ZERO).min(ONE);
    }

    private BigDecimal resolveLiquidityScore(EnrichedStrategyContext context) {
        MarketQualitySnapshot quality = context != null ? context.getMarketQualitySnapshot() : null;
        return quality != null && quality.getLiquidityScore() != null ? quality.getLiquidityScore() : ZERO;
    }

    private String resolveRegimeLabel(EnrichedStrategyContext context, FeatureStore feature) {
        if (context != null && context.getRegimeSnapshot() != null && context.getRegimeSnapshot().getRegimeLabel() != null) {
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
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", "SCALP"))
                .build();
    }

    private StrategyDecision veto(EnrichedStrategyContext context, String vetoReason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .reason("PullbackRejectionScalp vetoed")
                .vetoed(Boolean.TRUE)
                .vetoReason(vetoReason)
                .jumpRiskScore(resolveJumpRisk(context))
                .liquidityScore(resolveLiquidityScore(context))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", "SCALP"))
                .build();
    }

    private Map<String, Object> buildEntryDiagnostics(
            EnrichedStrategyContext context,
            FeatureStore feature,
            BigDecimal entry,
            BigDecimal stop,
            BigDecimal tp,
            BigDecimal signalScore,
            BigDecimal confidenceScore
    ) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("strategyCode", STRATEGY_CODE);
        diagnostics.put("strategyVersion", STRATEGY_VERSION);
        diagnostics.put("engine", "PULLBACK_REJECTION_SCALP");
        diagnostics.put("entryPrice", entry);
        diagnostics.put("stopLoss", stop);
        diagnostics.put("tp1", tp);
        diagnostics.put("signalScore", signalScore);
        diagnostics.put("confidenceScore", confidenceScore);
        diagnostics.put("relativeVolume20", feature.getRelativeVolume20() != null ? feature.getRelativeVolume20() : ZERO);
        diagnostics.put("bodyToRangeRatio", feature.getBodyToRangeRatio() != null ? feature.getBodyToRangeRatio() : ZERO);
        diagnostics.put("adx", feature.getAdx() != null ? feature.getAdx() : ZERO);
        diagnostics.put("rsi", feature.getRsi() != null ? feature.getRsi() : ZERO);
        diagnostics.put("macdHistogram", feature.getMacdHistogram() != null ? feature.getMacdHistogram() : ZERO);
        diagnostics.put("ema20", feature.getEma20() != null ? feature.getEma20() : ZERO);
        diagnostics.put("ema50", feature.getEma50() != null ? feature.getEma50() : ZERO);

        if (context != null && context.getDiagnostics() != null) {
            diagnostics.putAll(context.getDiagnostics());
        }

        return diagnostics;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isRiskUsable(BigDecimal close, BigDecimal risk) {
        return risk.compareTo(ZERO) > 0
                && risk.compareTo(close.multiply(MAX_RISK_PCT_OF_PRICE)) <= 0;
    }

    private boolean has(BigDecimal value) {
        return value != null;
    }

    private boolean inRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal cap(BigDecimal value) {
        return safe(value).max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }
}