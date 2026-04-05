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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ScalpMomV1StrategyService — v3
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  FIXES FROM v2  (268 trades, 50% WR, -$2.68 net)                       │
 * ├──────────┬──────────────────────────────────────────────────────────────┤
 * │ FIX 1   │ TP raised: 0.8 ATR → 1.0 ATR (= 1.25R vs 0.8 ATR stop)      │
 * │         │ ROOT CAUSE of v2 loss: avg win $0.090 ≈ avg loss $0.096.     │
 * │         │ After fees ($0.014/trade), RR = 0.878x — losing by design.   │
 * │         │ Stop=0.8 ATR, TP=1.0 ATR gives true 1.25x RR to overcome     │
 * │         │ fees and generate positive expectancy.                        │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 2   │ ADX tightened to 28–38 window                                │
 * │         │ v2 data: ADX <25 = -$2.59, ADX 30-35 = +$1.62 (62% WR)      │
 * │         │ ADX >40 also bleeds — overextended moves mean-revert fast.   │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 3   │ RSI tightened: long 50–62, short 38–50                       │
 * │         │ RSI 50-55: 84% WR, +$1.03 on 13 v2 trades (best bucket)     │
 * │         │ RSI <40: -$2.74 net on 108 trades (biggest drain in v2).     │
 * │         │ Tighter RSI bands eliminate both chasing and counter-trend.  │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 4   │ Shorts require stricter RSI: 42–50 only (vs 38–50)           │
 * │         │ v2: LONG 57.7% WR +$0.80 vs SHORT 47.2% WR -$3.48.          │
 * │         │ Short RSI <42 entries are the main source of losses.         │
 * │         │ Keeping shorts but requiring RSI closer to 50 (less extreme).│
 * └──────────┴──────────────────────────────────────────────────────────────┘
 *
 * Unchanged from v2:
 *  - SINGLE exit structure (TP1_RUNNER removed — confirmed right for 5min)
 *  - Session filter: allowed hours 02,06,10,11,12,13,17,22 UTC
 *  - Regime alignment: BULL=longs only, BEAR=shorts only
 *  - Stop: 0.8x ATR
 *  - Break-even trigger: 0.60R
 *  - 1h bias timeframe
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScalpMomV1StrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;

    // ── Identity ─────────────────────────────────────────────────────────────
    private static final String STRATEGY_CODE    = "SCALP_MOM_V1";
    private static final String STRATEGY_NAME    = "Scalp Momentum";
    private static final String STRATEGY_VERSION = "v3";

    // ── Sides ─────────────────────────────────────────────────────────────────
    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    // ── Signal types ──────────────────────────────────────────────────────────
    private static final String SIGNAL_TYPE_TREND               = "TREND";
    private static final String SIGNAL_TYPE_POSITION_MANAGEMENT = "POSITION_MANAGEMENT";

    // ── Setup labels ──────────────────────────────────────────────────────────
    private static final String SETUP_LONG            = "SCALP_LONG_CONTINUATION";
    private static final String SETUP_SHORT           = "SCALP_SHORT_CONTINUATION";
    private static final String SETUP_LONG_BREAK_EVEN  = "SCALP_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BREAK_EVEN = "SCALP_SHORT_BREAK_EVEN";

    private static final String EXIT_STRUCTURE = "SINGLE";
    private static final String TARGET_ALL     = "ALL";

    // ── Risk parameters ───────────────────────────────────────────────────────
    // Stop: 0.8x ATR (unchanged from v2 — working well, avg loss near 1R)
    private static final BigDecimal DEFAULT_STOP_ATR_MULT = new BigDecimal("0.80");

    // FIX 1: TP raised from 0.8 ATR (1.0R) → 1.0 ATR (1.25R)
    // TP in ATR units, not R — deliberately larger than stop to beat fees
    private static final BigDecimal DEFAULT_TP_ATR_MULT = new BigDecimal("1.00");

    // Break-even: trigger at 0.60R (unchanged — fast protection on 5min)
    private static final BigDecimal DEFAULT_BREAK_EVEN_R = new BigDecimal("0.60");

    // ── Score thresholds ──────────────────────────────────────────────────────
    private static final BigDecimal DEFAULT_MIN_SIGNAL_SCORE     = new BigDecimal("0.55");
    private static final BigDecimal DEFAULT_MIN_CONFIDENCE_SCORE = new BigDecimal("0.55");

    // ── FIX 2: ADX window tightened to 28–38 ─────────────────────────────────
    // v2: ADX 30-35 = WR 62%, +$1.62 | ADX <25 = -$2.59 | ADX >40 = bleeding
    private static final BigDecimal ADX_MIN = new BigDecimal("28");
    private static final BigDecimal ADX_MAX = new BigDecimal("38");

    // ── FIX 3 & 4: RSI bounds ────────────────────────────────────────────────
    // LONG: RSI 50–62  (v2: RSI 50-55 = 84% WR, RSI <40 = -$2.74)
    // SHORT: RSI 42–50 (FIX 4: stricter lower bound vs v2's 38)
    private static final BigDecimal RSI_LONG_MIN   = new BigDecimal("50");
    private static final BigDecimal RSI_LONG_MAX   = new BigDecimal("62");
    private static final BigDecimal RSI_SHORT_MAX  = new BigDecimal("50");
    private static final BigDecimal RSI_SHORT_MIN  = new BigDecimal("42"); // FIX 4: was 38

    // Score bonus thresholds (tighter band = higher quality)
    private static final BigDecimal RSI_LONG_SCORE_MIN  = new BigDecimal("51");
    private static final BigDecimal RSI_SHORT_SCORE_MAX = new BigDecimal("49");

    private static final BigDecimal VOLUME_SCORE_MIN = new BigDecimal("0.75");

    // ── Session filter (unchanged from v2) ───────────────────────────────────
    // Only allowed UTC hours with positive net PnL in v1+v2 data:
    //   02h: WR 71% +$0.52  |  06h: WR 58% +$0.57
    //   10h: marginal        |  11h: marginal
    //   12h: marginal        |  13h: WR 59% +$0.77
    //   17h: WR 64% +$1.20  |  22h: marginal
    private static final Set<Integer> ALLOWED_HOURS_UTC = Set.of(2, 6, 10, 11, 12, 13, 17, 22);

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;

    // ════════════════════════════════════════════════════════════════════════
    // Requirements
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public StrategyRequirements getRequirements() {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval("1h")
                .requireRegimeSnapshot(true)
                .requireVolatilitySnapshot(true)
                .requireRiskSnapshot(true)
                .requireMarketQualitySnapshot(true)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Main execute
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public StrategyDecision execute(EnrichedStrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(context, "Invalid context or missing market/feature data");
        }

        MarketData marketData     = context.getMarketData();
        FeatureStore feature      = context.getFeatureStore();
        PositionSnapshot snapshot = context.getPositionSnapshot();

        BigDecimal closePrice = strategyHelper.safe(marketData.getClosePrice());
        if (closePrice.compareTo(ZERO) <= 0) {
            return hold(context, "Close price is invalid");
        }

        if (isMarketVetoed(context)) {
            return veto("Market vetoed by quality or jump-risk filter", context);
        }

        // Manage open position — session filter does NOT apply to management
        if (context.hasTradablePosition() && snapshot != null) {
            return manageOpenPosition(context, snapshot);
        }

        // Session filter
        if (!isAllowedBySessionFilter(marketData)) {
            return hold(context, "Entry blocked — outside allowed UTC session hours");
        }

        if (context.isLongAllowed()) {
            StrategyDecision longDecision = tryBuildLongEntry(context, marketData, feature);
            if (longDecision != null) return longDecision;
        }

        if (context.isShortAllowed()) {
            StrategyDecision shortDecision = tryBuildShortEntry(context, marketData, feature);
            if (shortDecision != null) return shortDecision;
        }

        return hold(context, "No qualified SCALP_MOM setup on 5min");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Session Filter
    // ════════════════════════════════════════════════════════════════════════

    private boolean isAllowedBySessionFilter(MarketData marketData) {
        if (marketData.getEndTime() == null) return true;
        int hour = marketData.getEndTime().getHour();
        boolean allowed = ALLOWED_HOURS_UTC.contains(hour);
        if (!allowed) log.debug("ScalpMom session filter blocked entry at UTC hour {}", hour);
        return allowed;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Regime Alignment
    // ════════════════════════════════════════════════════════════════════════

    private boolean isRegimeBullish(EnrichedStrategyContext context) {
        RegimeSnapshot r = context.getRegimeSnapshot();
        return r != null && r.getRegimeLabel() != null
                && "BULL".equalsIgnoreCase(r.getRegimeLabel());
    }

    private boolean isRegimeBearish(EnrichedStrategyContext context) {
        RegimeSnapshot r = context.getRegimeSnapshot();
        return r != null && r.getRegimeLabel() != null
                && "BEAR".equalsIgnoreCase(r.getRegimeLabel());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Position Management Router
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision manageOpenPosition(
            EnrichedStrategyContext context,
            PositionSnapshot snapshot
    ) {
        String side = snapshot.getSide();
        if (side == null || snapshot.getEntryPrice() == null || snapshot.getCurrentStopLossPrice() == null) {
            return hold(context, "Open position exists but management inputs are incomplete");
        }

        if (SIDE_LONG.equalsIgnoreCase(side))  return manageLongPosition(context, context.getMarketData(), snapshot);
        if (SIDE_SHORT.equalsIgnoreCase(side)) return manageShortPosition(context, context.getMarketData(), snapshot);

        return hold(context, "Unknown open position side");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Entry Builders
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision tryBuildLongEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        // Regime alignment: block longs in BEAR regime
        if (isRegimeBearish(context)) return null;

        if (!isBullishTrend(context, feature, marketData)) return null;
        if (!isBullishEntryConfirmation(feature, marketData)) return null;

        BigDecimal entryPrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr         = resolveAtr(feature);

        // FIX 1: stop = 0.8 ATR, TP = 1.0 ATR → true RR = 1.25x
        BigDecimal stopLoss    = entryPrice.subtract(atr.multiply(resolveStopAtrMult(context)));
        BigDecimal takeProfit  = entryPrice.add(atr.multiply(resolveTpAtrMult(context)));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal signalScore     = calculateLongSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(DEFAULT_MIN_CONFIDENCE_SCORE) < 0) return null;

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_LONG);
        if (notionalSize.compareTo(ZERO) <= 0) return hold(context, "Calculated long notional is zero");

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_TREND).setupType(SETUP_LONG).side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("Bullish 5min scalp — session+regime+ADX28-38+RSI50-62 (v3)")
                .signalScore(signalScore).confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss).trailingStopPrice(null)
                .takeProfitPrice1(takeProfit).takeProfitPrice2(null).takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE).targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx()).entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi()).entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "SCALP_MOM", "LONG", "5MIN", "SINGLE", "V3"))
                .diagnostics(Map.of(
                        "module", "ScalpMomV1StrategyService",
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "takeProfit", takeProfit,
                        "atr", atr,
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
        // Regime alignment: block shorts in BULL regime
        if (isRegimeBullish(context)) return null;

        if (!isBearishTrend(context, feature, marketData)) return null;
        if (!isBearishEntryConfirmation(feature, marketData)) return null;

        BigDecimal entryPrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr         = resolveAtr(feature);

        // FIX 1: stop = 0.8 ATR, TP = 1.0 ATR → true RR = 1.25x
        BigDecimal stopLoss    = entryPrice.add(atr.multiply(resolveStopAtrMult(context)));
        BigDecimal takeProfit  = entryPrice.subtract(atr.multiply(resolveTpAtrMult(context)));
        BigDecimal riskPerUnit = stopLoss.subtract(entryPrice);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal signalScore     = calculateShortSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(DEFAULT_MIN_CONFIDENCE_SCORE) < 0) return null;

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_SHORT);
        if (notionalSize.compareTo(ZERO) <= 0) return hold(context, "Calculated short notional is zero");

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_TREND).setupType(SETUP_SHORT).side(SIDE_SHORT)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("Bearish 5min scalp — session+regime+ADX28-38+RSI42-50 (v3)")
                .signalScore(signalScore).confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss).trailingStopPrice(null)
                .takeProfitPrice1(takeProfit).takeProfitPrice2(null).takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE).targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx()).entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi()).entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "SCALP_MOM", "SHORT", "5MIN", "SINGLE", "V3"))
                .diagnostics(Map.of(
                        "module", "ScalpMomV1StrategyService",
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "takeProfit", takeProfit,
                        "atr", atr,
                        "signalScore", signalScore,
                        "confidenceScore", confidenceScore
                ))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Position Management — Break-Even (SINGLE exit, no runner)
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision manageLongPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice  = strategyHelper.safe(snapshot.getEntryPrice());
        BigDecimal currentStop = strategyHelper.safe(snapshot.getCurrentStopLossPrice());
        BigDecimal closePrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal initialRisk = entryPrice.subtract(currentStop);

        if (initialRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid long risk structure");

        BigDecimal breakEvenTrigger = initialRisk.multiply(resolveBreakEvenR(context));
        if (closePrice.subtract(entryPrice).compareTo(breakEvenTrigger) < 0)
            return hold(context, "Long not ready for break-even update");
        if (currentStop.compareTo(entryPrice) >= 0)
            return hold(context, "Long stop already at or above break-even");

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(SETUP_LONG_BREAK_EVEN).side(SIDE_LONG)
                .reason("Move long stop to break-even after 0.60R (v3)")
                .stopLossPrice(entryPrice).trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null).takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "SCALP_MOM", "LONG", "BREAK_EVEN", "V3"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk
                ))
                .build();
    }

    private StrategyDecision manageShortPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice  = strategyHelper.safe(snapshot.getEntryPrice());
        BigDecimal currentStop = strategyHelper.safe(snapshot.getCurrentStopLossPrice());
        BigDecimal closePrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal initialRisk = currentStop.subtract(entryPrice);

        if (initialRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid short risk structure");

        BigDecimal breakEvenTrigger = initialRisk.multiply(resolveBreakEvenR(context));
        if (entryPrice.subtract(closePrice).compareTo(breakEvenTrigger) < 0)
            return hold(context, "Short not ready for break-even update");
        if (currentStop.compareTo(entryPrice) <= 0)
            return hold(context, "Short stop already at or below break-even");

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(SETUP_SHORT_BREAK_EVEN).side(SIDE_SHORT)
                .reason("Move short stop to break-even after 0.60R (v3)")
                .stopLossPrice(entryPrice).trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null).takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "SCALP_MOM", "SHORT", "BREAK_EVEN", "V3"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk
                ))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Trend Filters (5min + 1h bias — unchanged)
    // ════════════════════════════════════════════════════════════════════════

    private boolean isBullishTrend(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket    = context.getBiasMarketData();

        boolean currentTrend = strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && strategyHelper.hasValue(feature.getEma200())
                && strategyHelper.hasValue(feature.getEma50Slope())
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0
                && feature.getEma50().compareTo(feature.getEma200()) > 0
                && feature.getEma50Slope().compareTo(ZERO) > 0
                && !"RANGE".equalsIgnoreCase(feature.getTrendRegime());

        boolean biasTrend = biasFeature == null || biasMarket == null || (
                strategyHelper.hasValue(biasMarket.getClosePrice())
                        && strategyHelper.hasValue(biasFeature.getEma50())
                        && strategyHelper.hasValue(biasFeature.getEma200())
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
        MarketData biasMarket    = context.getBiasMarketData();

        boolean currentTrend = strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && strategyHelper.hasValue(feature.getEma200())
                && strategyHelper.hasValue(feature.getEma50Slope())
                && marketData.getClosePrice().compareTo(feature.getEma50()) < 0
                && feature.getEma50().compareTo(feature.getEma200()) < 0
                && feature.getEma50Slope().compareTo(ZERO) < 0
                && !"RANGE".equalsIgnoreCase(feature.getTrendRegime());

        boolean biasTrend = biasFeature == null || biasMarket == null || (
                strategyHelper.hasValue(biasMarket.getClosePrice())
                        && strategyHelper.hasValue(biasFeature.getEma50())
                        && strategyHelper.hasValue(biasFeature.getEma200())
                        && biasMarket.getClosePrice().compareTo(biasFeature.getEma50()) < 0
                        && biasFeature.getEma50().compareTo(biasFeature.getEma200()) < 0
                        && !"RANGE".equalsIgnoreCase(biasFeature.getTrendRegime())
        );

        return currentTrend && biasTrend;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Entry Confirmation
    // FIX 2: ADX window 28–38 (not just a minimum)
    // FIX 3: RSI long 50–62
    // FIX 4: RSI short 42–50 (stricter lower bound)
    // ════════════════════════════════════════════════════════════════════════

    private boolean isBullishEntryConfirmation(FeatureStore feature, MarketData marketData) {
        if (!strategyHelper.hasValue(feature.getMacdHistogram())
                || !strategyHelper.hasValue(feature.getRsi())
                || !strategyHelper.hasValue(feature.getAdx())
                || !strategyHelper.hasValue(marketData.getClosePrice())
                || !strategyHelper.hasValue(feature.getEma50())) return false;

        BigDecimal adx = feature.getAdx();
        BigDecimal rsi = feature.getRsi();

        // FIX 2: ADX window 28–38
        if (adx.compareTo(ADX_MIN) < 0 || adx.compareTo(ADX_MAX) > 0) return false;

        // FIX 3: RSI 50–62 for longs
        if (rsi.compareTo(RSI_LONG_MIN) < 0 || rsi.compareTo(RSI_LONG_MAX) >= 0) return false;

        return feature.getMacdHistogram().compareTo(ZERO) > 0
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0;
    }

    private boolean isBearishEntryConfirmation(FeatureStore feature, MarketData marketData) {
        if (!strategyHelper.hasValue(feature.getMacdHistogram())
                || !strategyHelper.hasValue(feature.getRsi())
                || !strategyHelper.hasValue(feature.getAdx())
                || !strategyHelper.hasValue(marketData.getClosePrice())
                || !strategyHelper.hasValue(feature.getEma50())) return false;

        BigDecimal adx = feature.getAdx();
        BigDecimal rsi = feature.getRsi();

        // FIX 2: ADX window 28–38
        if (adx.compareTo(ADX_MIN) < 0 || adx.compareTo(ADX_MAX) > 0) return false;

        // FIX 4: RSI 42–50 for shorts (stricter than v2's 38–50)
        if (rsi.compareTo(RSI_SHORT_MAX) > 0 || rsi.compareTo(RSI_SHORT_MIN) <= 0) return false;

        return feature.getMacdHistogram().compareTo(ZERO) < 0
                && marketData.getClosePrice().compareTo(feature.getEma50()) < 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Signal Scoring
    // ════════════════════════════════════════════════════════════════════════

    private BigDecimal calculateLongSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        BigDecimal score = new BigDecimal("0.35");

        // ADX in sweet zone 30–38 gets full bonus; 28–30 gets partial
        if (strategyHelper.hasValue(feature.getAdx())) {
            BigDecimal adx = feature.getAdx();
            if (adx.compareTo(new BigDecimal("30")) >= 0 && adx.compareTo(ADX_MAX) <= 0)
                score = score.add(new BigDecimal("0.10"));
            else if (adx.compareTo(ADX_MIN) >= 0)
                score = score.add(new BigDecimal("0.05"));
        }

        if (strategyHelper.hasValue(feature.getRsi())
                && feature.getRsi().compareTo(RSI_LONG_SCORE_MIN) >= 0
                && feature.getRsi().compareTo(RSI_LONG_MAX) < 0)
            score = score.add(new BigDecimal("0.10"));

        if (strategyHelper.hasValue(feature.getMacdHistogram())
                && feature.getMacdHistogram().compareTo(ZERO) > 0)
            score = score.add(new BigDecimal("0.15"));

        if (strategyHelper.hasValue(feature.getEma50Slope())
                && feature.getEma50Slope().compareTo(ZERO) > 0)
            score = score.add(new BigDecimal("0.10"));

        if (strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0)
            score = score.add(new BigDecimal("0.10"));

        if (context.getRegimeSnapshot() != null
                && context.getRegimeSnapshot().getTrendScore() != null
                && context.getRegimeSnapshot().getTrendScore().compareTo(ZERO) > 0)
            score = score.add(new BigDecimal("0.10"));

        if (context.getMarketQualitySnapshot() != null
                && context.getMarketQualitySnapshot().getVolumeScore() != null
                && context.getMarketQualitySnapshot().getVolumeScore().compareTo(VOLUME_SCORE_MIN) >= 0)
            score = score.add(new BigDecimal("0.10"));

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        BigDecimal score = new BigDecimal("0.35");

        if (strategyHelper.hasValue(feature.getAdx())) {
            BigDecimal adx = feature.getAdx();
            if (adx.compareTo(new BigDecimal("30")) >= 0 && adx.compareTo(ADX_MAX) <= 0)
                score = score.add(new BigDecimal("0.10"));
            else if (adx.compareTo(ADX_MIN) >= 0)
                score = score.add(new BigDecimal("0.05"));
        }

        if (strategyHelper.hasValue(feature.getRsi())
                && feature.getRsi().compareTo(RSI_SHORT_SCORE_MAX) <= 0
                && feature.getRsi().compareTo(RSI_SHORT_MIN) > 0)
            score = score.add(new BigDecimal("0.10"));

        if (strategyHelper.hasValue(feature.getMacdHistogram())
                && feature.getMacdHistogram().compareTo(ZERO) < 0)
            score = score.add(new BigDecimal("0.15"));

        if (strategyHelper.hasValue(feature.getEma50Slope())
                && feature.getEma50Slope().compareTo(ZERO) < 0)
            score = score.add(new BigDecimal("0.10"));

        if (strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && marketData.getClosePrice().compareTo(feature.getEma50()) < 0)
            score = score.add(new BigDecimal("0.10"));

        if (context.getRegimeSnapshot() != null
                && context.getRegimeSnapshot().getTrendScore() != null
                && context.getRegimeSnapshot().getTrendScore().compareTo(ZERO) > 0)
            score = score.add(new BigDecimal("0.10"));

        if (context.getMarketQualitySnapshot() != null
                && context.getMarketQualitySnapshot().getVolumeScore() != null
                && context.getMarketQualitySnapshot().getVolumeScore().compareTo(VOLUME_SCORE_MIN) >= 0)
            score = score.add(new BigDecimal("0.10"));

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateConfidenceScore(EnrichedStrategyContext context, BigDecimal signalScore) {
        BigDecimal confidence = strategyHelper.safe(signalScore);

        confidence = confidence
                .add(resolveRegimeScore(context).multiply(new BigDecimal("0.20")))
                .add(resolveRiskMultiplier(context).multiply(new BigDecimal("0.10")));

        if (resolveJumpRisk(context).compareTo(new BigDecimal("0.40")) > 0) {
            confidence = confidence.subtract(new BigDecimal("0.20"));
        }

        return confidence.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Veto
    // ════════════════════════════════════════════════════════════════════════

    private boolean isMarketVetoed(EnrichedStrategyContext context) {
        if (context.getMarketQualitySnapshot() != null
                && Boolean.FALSE.equals(context.getMarketQualitySnapshot().getTradable())) return true;
        VolatilitySnapshot vol = context.getVolatilitySnapshot();
        return vol != null
                && vol.getJumpRiskScore() != null
                && context.getRuntimeConfig() != null
                && context.getRuntimeConfig().getMaxJumpRiskScore() != null
                && vol.getJumpRiskScore().compareTo(context.getRuntimeConfig().getMaxJumpRiskScore()) > 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Resolvers
    // ════════════════════════════════════════════════════════════════════════

    private BigDecimal resolveAtr(FeatureStore feature) {
        return (feature != null && feature.getAtr() != null && feature.getAtr().compareTo(ZERO) > 0)
                ? feature.getAtr() : ONE;
    }

    private BigDecimal resolveStopAtrMult(EnrichedStrategyContext context) {
        BigDecimal v = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("stopAtrMult") : null;
        return (v != null && v.compareTo(ZERO) > 0) ? v : DEFAULT_STOP_ATR_MULT;
    }

    /**
     * FIX 1: TP is now expressed in ATR units (not R units) so it's always
     * larger than the stop in absolute price terms: stop=0.8 ATR, TP=1.0 ATR → RR=1.25x
     */
    private BigDecimal resolveTpAtrMult(EnrichedStrategyContext context) {
        BigDecimal v = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("tpAtrMult") : null;
        return (v != null && v.compareTo(ZERO) > 0) ? v : DEFAULT_TP_ATR_MULT;
    }

    private BigDecimal resolveBreakEvenR(EnrichedStrategyContext context) {
        BigDecimal v = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("breakEvenR") : null;
        return (v != null && v.compareTo(ZERO) > 0) ? v : DEFAULT_BREAK_EVEN_R;
    }

    private BigDecimal resolveMinSignalScore(EnrichedStrategyContext context) {
        BigDecimal v = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getMinSignalScore() : null;
        return v != null ? v : DEFAULT_MIN_SIGNAL_SCORE;
    }

    private BigDecimal resolveRiskMultiplier(EnrichedStrategyContext context) {
        RiskSnapshot r = context.getRiskSnapshot();
        return (r != null && r.getRiskMultiplier() != null) ? r.getRiskMultiplier() : ONE;
    }

    private BigDecimal resolveRegimeScore(EnrichedStrategyContext context) {
        RegimeSnapshot r = context.getRegimeSnapshot();
        return (r != null && r.getTrendScore() != null) ? r.getTrendScore() : ZERO;
    }

    private BigDecimal resolveJumpRisk(EnrichedStrategyContext context) {
        VolatilitySnapshot v = context.getVolatilitySnapshot();
        return (v != null && v.getJumpRiskScore() != null) ? v.getJumpRiskScore() : ZERO;
    }

    private String resolveRegimeLabel(EnrichedStrategyContext context, FeatureStore feature) {
        if (context.getRegimeSnapshot() != null && context.getRegimeSnapshot().getRegimeLabel() != null)
            return context.getRegimeSnapshot().getRegimeLabel();
        return feature != null ? feature.getTrendRegime() : null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Hold / Veto
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType("SCALP_MOM").reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", "SCALP_MOM"))
                .build();
    }

    private StrategyDecision veto(String vetoReason, EnrichedStrategyContext context) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .regimeLabel(context != null && context.getRegimeSnapshot() != null
                        ? context.getRegimeSnapshot().getRegimeLabel() : null)
                .vetoed(Boolean.TRUE).vetoReason(vetoReason)
                .reason("Decision vetoed by risk layer")
                .jumpRiskScore(resolveJumpRisk(context))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", "SCALP_MOM", "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }
}