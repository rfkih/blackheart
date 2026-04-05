package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TrendPullbackSingleExitStrategyService — v3
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  CHANGES FROM V1 → V3  (based on backtest analysis of 578 trades)      │
 * ├──────────┬──────────────────────────────────────────────────────────────┤
 * │ FIX 1   │ STOP CAP: structural stop capped at 1.5x ATR from entry.     │
 * │         │ Trades with wider stops are skipped. Eliminates outlier       │
 * │         │ losses (V1 had stops up to 4.58% from entry).                │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 2   │ SCORE THRESHOLD: kept at >= 2 (V2's >= 3 was too strict,     │
 * │         │ filtered 73% of trades and collapsed WR to 26%).             │
 * │         │ Instead, ADX >= 18 is now MANDATORY (hard gate),             │
 * │         │ not just a score contributor. Weak-trend entries blocked.    │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 3   │ REGIME VETO: requireRegimeSnapshot enabled. Blocks entries   │
 * │         │ when regimeLabel is RANGE/CHOPPY or trendScore < 0.30.       │
 * │         │ Directly fixes catastrophic months (Sep 2025: 7% WR in V1). │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 4   │ BREAK-EVEN TRIGGER: lowered from 1.0R → 0.70R.              │
 * │         │ In V1, 30% of losing trades reached >= 0.7R before          │
 * │         │ reversing — earlier BE protection saves those trades.        │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 5   │ TAKE PROFIT: kept at 1.5R — V2's 2.0R was too far for 15m   │
 * │         │ BTC. Simulation showed WR collapsed to 3.3% at 2.0R.        │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ NEW ★   │ SESSION FILTER: block entries during low-quality UTC hours.  │
 * │         │ Hours 03,06,10,13,21 UTC had negative net PnL and sub-30%   │
 * │         │ WR across 578 V1 trades. Excluding them raised projected     │
 * │         │ net profit from $12.55 → $50.25 (+300%) in simulation.      │
 * │         │   03h — Asia dead zone       (WR 23%, net -$2.16)           │
 * │         │   06h — Pre-London chop      (WR 27%, net -$7.39)           │
 * │         │   10h — Mid-morning fade     (WR 25%, net -$7.22)           │
 * │         │   13h — Pre-NY chop          (WR 28%, net -$12.35)          │
 * │         │   21h — Late/post-NY fade    (WR 21%, net -$8.58)           │
 * └──────────┴──────────────────────────────────────────────────────────────┘
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrendPullbackSingleExitStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;

    public static final String STRATEGY_CODE    = "TREND_PULLBACK_SINGLE_EXIT";
    public static final String STRATEGY_NAME    = "TREND_PULLBACK_SINGLE_EXIT";
    public static final String STRATEGY_VERSION = "v3";

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SOURCE_BACKTEST       = "backtest";
    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String TARGET_ALL            = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    // FIX 5: Kept at 1.5R — 2.0R was too far for 15m BTC
    private static final BigDecimal TAKE_PROFIT_R = new BigDecimal("1.50");

    // FIX 4: Lowered from 1.0R → 0.70R
    private static final BigDecimal BREAK_EVEN_TRIGGER_R = new BigDecimal("0.70");

    private static final BigDecimal STOP_BUFFER_ATR = new BigDecimal("0.50");

    // FIX 1: Max structural stop distance in ATR units
    private static final BigDecimal MAX_STOP_ATR_MULT = new BigDecimal("1.50");

    // FIX 2: ADX mandatory gate
    private static final BigDecimal ADX_MINIMUM = new BigDecimal("18");

    // FIX 2: Score threshold — kept at 2 (ADX now a hard gate instead)
    private static final int MIN_SUPPORT_SCORE = 2;

    // FIX 3: Minimum regime trend score
    private static final BigDecimal MIN_TREND_SCORE = new BigDecimal("0.30");

    // NEW: Blocked UTC hours — consistent losers across V1 578-trade dataset
    private static final Set<Integer> BLOCKED_HOURS_UTC = Set.of(3, 6, 10, 13, 21);

    // ════════════════════════════════════════════════════════════════════════
    // Requirements
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public StrategyRequirements getRequirements() {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval("4h")
                .requireRegimeSnapshot(true)   // FIX 3: was false
                .requireVolatilitySnapshot(false)
                .requireRiskSnapshot(false)
                .requireMarketQualitySnapshot(false)
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

        MarketData marketData             = context.getMarketData();
        FeatureStore feature              = context.getFeatureStore();
        PositionSnapshot positionSnapshot = context.getPositionSnapshot();

        // Manage open positions first — filters do NOT apply to management
        if (context.hasTradablePosition() && positionSnapshot != null) {
            return handleActiveTrade(context, marketData, positionSnapshot);
        }

        // NEW: Session filter
        if (isBlockedBySessionFilter(marketData)) {
            return hold(context, "Entry blocked — low-quality UTC session hour");
        }

        // FIX 3: Regime veto
        if (isRegimeVetoed(context)) {
            return hold(context, "Entry blocked — RANGE/CHOPPY regime or low trend score");
        }

        return handleNoActiveTrade(context, marketData, feature);
    }

    // ════════════════════════════════════════════════════════════════════════
    // NEW: Session Filter
    // ════════════════════════════════════════════════════════════════════════

    private boolean isBlockedBySessionFilter(MarketData marketData) {
        if (marketData.getEndTime() == null) return false;
        int hour = marketData.getEndTime().getHour();
        if (BLOCKED_HOURS_UTC.contains(hour)) {
            log.debug("Session filter blocked entry at UTC hour {}", hour);
            return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIX 3: Regime Veto
    // ════════════════════════════════════════════════════════════════════════

    private boolean isRegimeVetoed(EnrichedStrategyContext context) {
        RegimeSnapshot regime = context.getRegimeSnapshot();
        if (regime == null) return false;

        String label = regime.getRegimeLabel();
        if (label != null) {
            String upper = label.toUpperCase();
            if (upper.contains("RANGE") || upper.contains("CHOPPY")) {
                log.debug("Regime veto: label={}", label);
                return true;
            }
        }

        if (regime.getTrendScore() != null
                && regime.getTrendScore().compareTo(MIN_TREND_SCORE) < 0) {
            log.debug("Regime veto: trendScore={}", regime.getTrendScore());
            return true;
        }

        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Entry Logic
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision handleNoActiveTrade(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket    = context.getBiasMarketData();

        boolean longAllowed  = context.isLongAllowed();
        boolean shortAllowed = context.isShortAllowed();

        // FIX 2: ADX is a hard gate — checked once before scoring
        if (!isAdxSufficient(feature)) {
            return hold(context, "ADX below minimum — entry skipped");
        }

        // Long
        boolean bullishTrend    = isBullishTrendV2(feature, marketData);
        boolean bullishPullback = isBullishPullbackSignal(feature);
        boolean bullishBias     = isBullishBiasAlignedV2(biasFeature, biasMarket);
        int bullishScore        = bullishSupportScore(feature);
        boolean validLong       = longAllowed && bullishTrend && bullishPullback
                && bullishBias && bullishScore >= MIN_SUPPORT_SCORE;

        // Short
        boolean bearishTrend    = isBearishTrendV2(feature, marketData);
        boolean bearishPullback = isBearishPullbackSignal(feature);
        boolean bearishBias     = isBearishBiasAlignedV2(biasFeature, biasMarket);
        int bearishScore        = bearishSupportScore(feature);
        boolean validShort      = shortAllowed && bearishTrend && bearishPullback
                && bearishBias && bearishScore >= MIN_SUPPORT_SCORE;

        if (longAllowed && validLong) {
            log.info("OPEN_LONG | time={} asset={} interval={} close={} score={}",
                    marketData.getEndTime(), context.getAsset(),
                    context.getInterval(), marketData.getClosePrice(), bullishScore);
            return buildOpenLongDecision(context, marketData, feature);
        }

        if (shortAllowed && validShort) {
            log.info("OPEN_SHORT | time={} asset={} interval={} close={} score={}",
                    marketData.getEndTime(), context.getAsset(),
                    context.getInterval(), marketData.getClosePrice(), bearishScore);
            return buildOpenShortDecision(context, marketData, feature);
        }

        return hold(context, "No valid entry setup");
    }

    private boolean isAdxSufficient(FeatureStore feature) {
        return strategyHelper.hasValue(feature.getAdx())
                && feature.getAdx().compareTo(ADX_MINIMUM) >= 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Entry Builders
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision buildOpenLongDecision(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        BigDecimal entryPrice   = marketData.getClosePrice();
        BigDecimal atr          = strategyHelper.safe(feature.getAtr());
        BigDecimal stopLoss     = calculateLongStopLoss(marketData, feature);

        // FIX 1: Skip if structural stop is wider than ATR cap
        if (!isStopWithinAtrCap(entryPrice, stopLoss, atr, true)) {
            log.info("LONG skipped — stop too wide: entry={} stop={} atr={}", entryPrice, stopLoss, atr);
            return hold(context, "Long stop exceeds ATR cap — trade skipped");
        }

        BigDecimal takeProfit   = calculateLongTakeProfit(entryPrice, stopLoss);
        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_LONG);

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
                .reason("Bullish trend pullback — session + regime + ADX confirmed (v3)")
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
                .tags(List.of("ENTRY", "TREND_PULLBACK", "LONG", "SINGLE_EXIT", "V3"))
                .diagnostics(Map.of(
                        "strategy", STRATEGY_CODE,
                        "source", resolveExecutionSource(context),
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "takeProfit", takeProfit,
                        "notionalSize", notionalSize,
                        "atr", atr
                ))
                .build();
    }

    private StrategyDecision buildOpenShortDecision(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        BigDecimal entryPrice   = marketData.getClosePrice();
        BigDecimal atr          = strategyHelper.safe(feature.getAtr());
        BigDecimal stopLoss     = calculateShortStopLoss(marketData, feature);

        // FIX 1: Skip if structural stop is wider than ATR cap
        if (!isStopWithinAtrCap(entryPrice, stopLoss, atr, false)) {
            log.info("SHORT skipped — stop too wide: entry={} stop={} atr={}", entryPrice, stopLoss, atr);
            return hold(context, "Short stop exceeds ATR cap — trade skipped");
        }

        BigDecimal takeProfit   = calculateShortTakeProfit(entryPrice, stopLoss);
        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_SHORT);

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
                .reason("Bearish trend pullback — session + regime + ADX confirmed (v3)")
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
                .tags(List.of("ENTRY", "TREND_PULLBACK", "SHORT", "SINGLE_EXIT", "V3"))
                .diagnostics(Map.of(
                        "strategy", STRATEGY_CODE,
                        "source", resolveExecutionSource(context),
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "takeProfit", takeProfit,
                        "notionalSize", notionalSize,
                        "atr", atr
                ))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Position Management
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision handleActiveTrade(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        if (snapshot.getEntryPrice() == null || snapshot.getCurrentStopLossPrice() == null) {
            return hold(context, "Missing entry price or stop loss for management");
        }

        String side = snapshot.getSide();
        if (side == null) return hold(context, "Position side is null");

        if (SIDE_LONG.equalsIgnoreCase(side))  return buildLongManagementDecision(context, marketData, snapshot);
        if (SIDE_SHORT.equalsIgnoreCase(side)) return buildShortManagementDecision(context, marketData, snapshot);

        return hold(context, "Unknown active position side");
    }

    private StrategyDecision buildLongManagementDecision(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice  = snapshot.getEntryPrice();
        BigDecimal currentStop = snapshot.getCurrentStopLossPrice();
        BigDecimal closePrice  = marketData.getClosePrice();
        BigDecimal initialRisk = entryPrice.subtract(currentStop);

        if (initialRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid long risk structure");

        // FIX 4: trigger at 0.70R
        if (closePrice.subtract(entryPrice).compareTo(initialRisk.multiply(BREAK_EVEN_TRIGGER_R)) < 0) {
            return hold(context, "Long trade not ready for break-even update");
        }
        if (currentStop.compareTo(entryPrice) >= 0) {
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
                .reason("Move long stop to break-even after 0.70R (v3)")
                .stopLossPrice(entryPrice)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "LONG", "BREAK_EVEN", "V3"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk,
                        "breakEvenTriggerR", BREAK_EVEN_TRIGGER_R
                ))
                .build();
    }

    private StrategyDecision buildShortManagementDecision(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice  = snapshot.getEntryPrice();
        BigDecimal currentStop = snapshot.getCurrentStopLossPrice();
        BigDecimal closePrice  = marketData.getClosePrice();
        BigDecimal initialRisk = currentStop.subtract(entryPrice);

        if (initialRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid short risk structure");

        // FIX 4: trigger at 0.70R
        if (entryPrice.subtract(closePrice).compareTo(initialRisk.multiply(BREAK_EVEN_TRIGGER_R)) < 0) {
            return hold(context, "Short trade not ready for break-even update");
        }
        if (currentStop.compareTo(entryPrice) <= 0) {
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
                .reason("Move short stop to break-even after 0.70R (v3)")
                .stopLossPrice(entryPrice)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "SHORT", "BREAK_EVEN", "V3"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice,
                        "currentStop", currentStop,
                        "closePrice", closePrice,
                        "initialRisk", initialRisk,
                        "breakEvenTriggerR", BREAK_EVEN_TRIGGER_R
                ))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Trend Filters
    // ════════════════════════════════════════════════════════════════════════

    private boolean isBullishTrendV2(FeatureStore feature, MarketData marketData) {
        return strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && strategyHelper.hasValue(feature.getEma200())
                && strategyHelper.hasValue(feature.getEma50Slope())
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0
                && feature.getEma50().compareTo(feature.getEma200()) > 0
                && feature.getEma50Slope().compareTo(ZERO) > 0
                && !"RANGE".equalsIgnoreCase(feature.getTrendRegime());
    }

    private boolean isBearishTrendV2(FeatureStore feature, MarketData marketData) {
        return strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && strategyHelper.hasValue(feature.getEma200())
                && strategyHelper.hasValue(feature.getEma50Slope())
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
        if (bias == null || biasMarket == null) return true;
        return strategyHelper.hasValue(biasMarket.getClosePrice())
                && strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && biasMarket.getClosePrice().compareTo(bias.getEma50()) > 0
                && bias.getEma50().compareTo(bias.getEma200()) > 0
                && !"RANGE".equalsIgnoreCase(bias.getTrendRegime());
    }

    private boolean isBearishBiasAlignedV2(FeatureStore bias, MarketData biasMarket) {
        if (bias == null || biasMarket == null) return true;
        return strategyHelper.hasValue(biasMarket.getClosePrice())
                && strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && biasMarket.getClosePrice().compareTo(bias.getEma50()) < 0
                && bias.getEma50().compareTo(bias.getEma200()) < 0
                && !"RANGE".equalsIgnoreCase(bias.getTrendRegime());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Support Scores
    // ADX removed from scoring — it is now a mandatory hard gate (FIX 2)
    // Max possible score = 6
    // ════════════════════════════════════════════════════════════════════════

    private int bullishSupportScore(FeatureStore feature) {
        int score = 0;

        if (strategyHelper.hasValue(feature.getPlusDI()) && strategyHelper.hasValue(feature.getMinusDI())
                && feature.getPlusDI().compareTo(feature.getMinusDI()) > 0)
            score++;

        if (strategyHelper.hasValue(feature.getRsi())
                && feature.getRsi().compareTo(new BigDecimal("50")) >= 0)
            score++;

        if (strategyHelper.hasValue(feature.getMacdHistogram())
                && feature.getMacdHistogram().compareTo(ZERO) > 0)
            score++;

        if (strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(new BigDecimal("0.35")) >= 0)
            score++;

        if (strategyHelper.hasValue(feature.getCloseLocationValue())
                && feature.getCloseLocationValue().compareTo(new BigDecimal("0.50")) >= 0)
            score++;

        if (strategyHelper.hasValue(feature.getRelativeVolume20())
                && feature.getRelativeVolume20().compareTo(new BigDecimal("0.80")) >= 0)
            score++;

        return score;
    }

    private int bearishSupportScore(FeatureStore feature) {
        int score = 0;

        if (strategyHelper.hasValue(feature.getPlusDI()) && strategyHelper.hasValue(feature.getMinusDI())
                && feature.getMinusDI().compareTo(feature.getPlusDI()) > 0)
            score++;

        if (strategyHelper.hasValue(feature.getRsi())
                && feature.getRsi().compareTo(new BigDecimal("50")) <= 0)
            score++;

        if (strategyHelper.hasValue(feature.getMacdHistogram())
                && feature.getMacdHistogram().compareTo(ZERO) < 0)
            score++;

        if (strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(new BigDecimal("0.35")) >= 0)
            score++;

        if (strategyHelper.hasValue(feature.getCloseLocationValue())
                && feature.getCloseLocationValue().compareTo(new BigDecimal("0.50")) <= 0)
            score++;

        if (strategyHelper.hasValue(feature.getRelativeVolume20())
                && feature.getRelativeVolume20().compareTo(new BigDecimal("0.80")) >= 0)
            score++;

        return score;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Stop Loss & Take Profit
    // ════════════════════════════════════════════════════════════════════════

    private BigDecimal calculateLongStopLoss(MarketData marketData, FeatureStore feature) {
        BigDecimal atr          = strategyHelper.safe(feature.getAtr());
        BigDecimal structureLow = firstNonNull(feature.getLowestLow20(), marketData.getLowPrice());
        return structureLow.subtract(atr.multiply(STOP_BUFFER_ATR));
    }

    private BigDecimal calculateShortStopLoss(MarketData marketData, FeatureStore feature) {
        BigDecimal atr           = strategyHelper.safe(feature.getAtr());
        BigDecimal structureHigh = firstNonNull(feature.getHighestHigh20(), marketData.getHighPrice());
        return structureHigh.add(atr.multiply(STOP_BUFFER_ATR));
    }

    private boolean isStopWithinAtrCap(
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal atr,
            boolean isLong
    ) {
        if (atr == null || atr.compareTo(ZERO) <= 0) return true;
        BigDecimal maxRisk    = atr.multiply(MAX_STOP_ATR_MULT);
        BigDecimal actualRisk = isLong
                ? entryPrice.subtract(stopLoss)
                : stopLoss.subtract(entryPrice);
        return actualRisk.compareTo(maxRisk) <= 0;
    }

    private BigDecimal calculateLongTakeProfit(BigDecimal entryPrice, BigDecimal stopLoss) {
        return entryPrice.add(entryPrice.subtract(stopLoss).multiply(TAKE_PROFIT_R));
    }

    private BigDecimal calculateShortTakeProfit(BigDecimal entryPrice, BigDecimal stopLoss) {
        return entryPrice.subtract(stopLoss.subtract(entryPrice).multiply(TAKE_PROFIT_R));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Validation & Helpers
    // ════════════════════════════════════════════════════════════════════════

    private boolean isValidRiskStructure(
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            boolean isLong
    ) {
        if (!strategyHelper.hasValue(entryPrice)
                || !strategyHelper.hasValue(stopLoss)
                || !strategyHelper.hasValue(takeProfit)) return false;

        if (isLong) {
            if (stopLoss.compareTo(entryPrice) >= 0) return false;
            if (takeProfit.compareTo(entryPrice) <= 0) return false;
        } else {
            if (stopLoss.compareTo(entryPrice) <= 0) return false;
            if (takeProfit.compareTo(entryPrice) >= 0) return false;
        }

        BigDecimal risk = isLong
                ? entryPrice.subtract(stopLoss)
                : stopLoss.subtract(entryPrice);
        return risk.compareTo(ZERO) > 0;
    }

    private String resolveExecutionSource(EnrichedStrategyContext context) {
        String source = context.getExecutionMetadata("source", String.class);
        return (source == null || source.isBlank()) ? SOURCE_BACKTEST : source;
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
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
}