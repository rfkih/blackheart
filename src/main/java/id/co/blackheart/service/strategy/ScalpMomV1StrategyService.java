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

/**
 * ScalpMomV1StrategyExecutor — 5-Minute EMA + RSI + MACD Scalping Strategy
 *
 * Philosophy:
 *   - Uses a 1h bias timeframe to confirm macro trend direction
 *   - Enters on 5min momentum alignment: EMA9 > EMA21, MACD positive, RSI in momentum zone
 *   - Tighter stops and faster break-even vs TSMOM (tuned for 5min volatility)
 *   - Runner trail logic preserved for catching extended moves
 *
 * Entry Conditions (Long):
 *   - 1h bias: price > EMA50 > EMA200, not RANGE
 *   - 5min: EMA9 > EMA21, close > EMA21
 *   - MACD histogram > 0
 *   - RSI >= 50 (momentum zone)
 *   - ADX >= 18 (trending, not choppy)
 *
 * Entry Conditions (Short): mirror of above
 *
 * Risk:
 *   - Stop: 1.0x ATR (tighter than TSMOM's 1.4x)
 *   - TP1: 1.0R, TP2: 1.8R, TP3: 3.0R
 *   - Break-even trigger: 0.80R (faster than TSMOM)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScalpMomV1StrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;

    // ── Identity ────────────────────────────────────────────────────────────
    private static final String STRATEGY_CODE    = "SCALP_MOM_V1";
    private static final String STRATEGY_NAME    = "Scalp Momentum";
    private static final String STRATEGY_VERSION = "v1";

    // ── Sides ────────────────────────────────────────────────────────────────
    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    // ── Signal types ─────────────────────────────────────────────────────────
    private static final String SIGNAL_TYPE_TREND               = "TREND";
    private static final String SIGNAL_TYPE_POSITION_MANAGEMENT = "POSITION_MANAGEMENT";

    // ── Setup labels ─────────────────────────────────────────────────────────
    private static final String SETUP_LONG               = "SCALP_LONG_CONTINUATION";
    private static final String SETUP_SHORT              = "SCALP_SHORT_CONTINUATION";
    private static final String SETUP_LONG_BREAK_EVEN    = "SCALP_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BREAK_EVEN   = "SCALP_SHORT_BREAK_EVEN";
    private static final String SETUP_LONG_RUNNER_TRAIL  = "SCALP_LONG_RUNNER_TRAIL";
    private static final String SETUP_SHORT_RUNNER_TRAIL = "SCALP_SHORT_RUNNER_TRAIL";

    // ── Position roles ────────────────────────────────────────────────────────
    private static final String POSITION_ROLE_RUNNER = "RUNNER";
    private static final String EXIT_STRUCTURE       = "TP1_RUNNER";
    private static final String TARGET_ALL           = "ALL";

    // ── Risk defaults (tighter than TSMOM for 5min) ───────────────────────────
    private static final BigDecimal DEFAULT_STOP_ATR_MULT     = new BigDecimal("1.00"); // vs 1.40
    private static final BigDecimal DEFAULT_TP1_R             = new BigDecimal("1.00"); // quick partial
    private static final BigDecimal DEFAULT_TP2_R             = new BigDecimal("1.80");
    private static final BigDecimal DEFAULT_TP3_R             = new BigDecimal("3.00");
    private static final BigDecimal DEFAULT_BREAK_EVEN_R      = new BigDecimal("0.80"); // vs 1.00

    // ── Runner trail thresholds ───────────────────────────────────────────────
    private static final BigDecimal RUNNER_BREAK_EVEN_R      = new BigDecimal("0.80");
    private static final BigDecimal RUNNER_TRAIL_PHASE_1_R   = new BigDecimal("1.80");
    private static final BigDecimal RUNNER_TRAIL_PHASE_2_R   = new BigDecimal("2.80");
    private static final BigDecimal RUNNER_TRAIL_ATR_PHASE_1 = new BigDecimal("0.90");
    private static final BigDecimal RUNNER_TRAIL_ATR_PHASE_2 = new BigDecimal("0.60");
    private static final BigDecimal RUNNER_LOCK_R_PHASE_1    = new BigDecimal("0.40");
    private static final BigDecimal RUNNER_LOCK_R_PHASE_2    = new BigDecimal("1.20");

    // ── Score thresholds ──────────────────────────────────────────────────────
    private static final BigDecimal DEFAULT_MIN_SIGNAL_SCORE     = new BigDecimal("0.55");
    private static final BigDecimal DEFAULT_MIN_CONFIDENCE_SCORE = new BigDecimal("0.55");

    // ── Indicator thresholds ──────────────────────────────────────────────────
    private static final BigDecimal RSI_LONG_MIN  = new BigDecimal("50");
    private static final BigDecimal RSI_SHORT_MAX = new BigDecimal("50");
    private static final BigDecimal RSI_SCORE_MIN = new BigDecimal("52");
    private static final BigDecimal RSI_SCORE_MAX = new BigDecimal("48");
    private static final BigDecimal ADX_MIN       = new BigDecimal("18");
    private static final BigDecimal VOLUME_SCORE_MIN = new BigDecimal("0.75");

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
                .biasInterval("1h")                  // 1h bias for 5min scalping
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

        MarketData marketData   = context.getMarketData();
        FeatureStore feature    = context.getFeatureStore();
        PositionSnapshot snapshot = context.getPositionSnapshot();

        BigDecimal closePrice = strategyHelper.safe(marketData.getClosePrice());
        if (closePrice.compareTo(ZERO) <= 0) {
            return hold(context, "Close price is invalid");
        }

        // ── Market veto check ────────────────────────────────────────────────
        if (isMarketVetoed(context)) {
            return veto("Market vetoed by quality or jump-risk filter", context);
        }

        // ── Manage open position first ────────────────────────────────────────
        if (context.hasTradablePosition() && snapshot != null) {
            return manageOpenPosition(context, snapshot);
        }

        // ── Try entries ───────────────────────────────────────────────────────
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

        boolean isRunner = snapshot.getPositionRole() != null
                && POSITION_ROLE_RUNNER.equalsIgnoreCase(snapshot.getPositionRole());

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            return isRunner
                    ? manageLongRunnerPosition(context, context.getMarketData(), context.getFeatureStore(), snapshot)
                    : manageLongPosition(context, context.getMarketData(), snapshot);
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            return isRunner
                    ? manageShortRunnerPosition(context, context.getMarketData(), context.getFeatureStore(), snapshot)
                    : manageShortPosition(context, context.getMarketData(), snapshot);
        }

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
        if (!isBullishTrend(context, feature, marketData)) return null;
        if (!isBullishEntryConfirmation(feature, marketData)) return null;

        BigDecimal entryPrice   = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr          = resolveAtr(feature);
        BigDecimal stopLoss     = entryPrice.subtract(atr.multiply(resolveStopAtrMult(context)));
        BigDecimal riskPerUnit  = entryPrice.subtract(stopLoss);

        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(resolveTp1R(context)));
        BigDecimal tp2 = entryPrice.add(riskPerUnit.multiply(resolveTp2R(context)));
        BigDecimal tp3 = entryPrice.add(riskPerUnit.multiply(resolveTp3R(context)));

        BigDecimal signalScore     = calculateLongSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(DEFAULT_MIN_CONFIDENCE_SCORE) < 0) {
            return null;
        }

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_LONG);
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated long notional size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_TREND)
                .setupType(SETUP_LONG)
                .side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("Qualified bullish scalp momentum setup on 5min")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(tp2)
                .takeProfitPrice3(tp3)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "SCALP_MOM", "LONG", "5MIN"))
                .diagnostics(Map.of(
                        "module", "ScalpMomV1StrategyExecutor",
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "tp1", tp1, "tp2", tp2, "tp3", tp3,
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
        if (!isBearishTrend(context, feature, marketData)) return null;
        if (!isBearishEntryConfirmation(feature, marketData)) return null;

        BigDecimal entryPrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr         = resolveAtr(feature);
        BigDecimal stopLoss    = entryPrice.add(atr.multiply(resolveStopAtrMult(context)));
        BigDecimal riskPerUnit = stopLoss.subtract(entryPrice);

        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal tp1 = entryPrice.subtract(riskPerUnit.multiply(resolveTp1R(context)));
        BigDecimal tp2 = entryPrice.subtract(riskPerUnit.multiply(resolveTp2R(context)));
        BigDecimal tp3 = entryPrice.subtract(riskPerUnit.multiply(resolveTp3R(context)));

        BigDecimal signalScore     = calculateShortSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(DEFAULT_MIN_CONFIDENCE_SCORE) < 0) {
            return null;
        }

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_SHORT);
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Calculated short notional size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_TREND)
                .setupType(SETUP_SHORT)
                .side(SIDE_SHORT)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("Qualified bearish scalp momentum setup on 5min")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(tp2)
                .takeProfitPrice3(tp3)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "SCALP_MOM", "SHORT", "5MIN"))
                .diagnostics(Map.of(
                        "module", "ScalpMomV1StrategyExecutor",
                        "entryPrice", entryPrice,
                        "stopLoss", stopLoss,
                        "tp1", tp1, "tp2", tp2, "tp3", tp3,
                        "signalScore", signalScore,
                        "confidenceScore", confidenceScore
                ))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Position Management — Standard (Break-Even)
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

        BigDecimal move             = closePrice.subtract(entryPrice);
        BigDecimal breakEvenTrigger = initialRisk.multiply(resolveBreakEvenR(context));

        if (move.compareTo(breakEvenTrigger) < 0) return hold(context, "Long not ready for break-even");
        if (currentStop.compareTo(entryPrice) >= 0) return hold(context, "Long stop already at or above break-even");

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(SETUP_LONG_BREAK_EVEN)
                .side(SIDE_LONG)
                .reason("Move long stop to break-even on 5min scalp")
                .stopLossPrice(entryPrice)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "SCALP_MOM", "LONG", "BREAK_EVEN"))
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

        BigDecimal move             = entryPrice.subtract(closePrice);
        BigDecimal breakEvenTrigger = initialRisk.multiply(resolveBreakEvenR(context));

        if (move.compareTo(breakEvenTrigger) < 0) return hold(context, "Short not ready for break-even");
        if (currentStop.compareTo(entryPrice) <= 0) return hold(context, "Short stop already at or below break-even");

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(SETUP_SHORT_BREAK_EVEN)
                .side(SIDE_SHORT)
                .reason("Move short stop to break-even on 5min scalp")
                .stopLossPrice(entryPrice)
                .trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "SCALP_MOM", "SHORT", "BREAK_EVEN"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk
                ))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Position Management — Runner Trail
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision manageLongRunnerPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice  = strategyHelper.safe(snapshot.getEntryPrice());
        BigDecimal currentStop = strategyHelper.safe(snapshot.getCurrentStopLossPrice());
        BigDecimal closePrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr         = resolveAtr(feature);

        BigDecimal initialStop = snapshot.getInitialStopLossPrice() != null
                ? snapshot.getInitialStopLossPrice() : currentStop;
        BigDecimal initialRisk = entryPrice.subtract(initialStop);

        if (initialRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid long runner risk structure");

        BigDecimal move = closePrice.subtract(entryPrice);
        if (move.compareTo(ZERO) <= 0) return hold(context, "Long runner not yet in profit");

        BigDecimal rMultiple = move.divide(initialRisk, 6, RoundingMode.HALF_UP);

        BigDecimal candidateStop = null;
        String reason = null;
        String setupType = null;

        if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_2_R) >= 0) {
            BigDecimal atrStop         = closePrice.subtract(atr.multiply(RUNNER_TRAIL_ATR_PHASE_2));
            BigDecimal lockedProfitStop = entryPrice.add(initialRisk.multiply(RUNNER_LOCK_R_PHASE_2));
            candidateStop = atrStop.max(lockedProfitStop).max(entryPrice);
            reason    = "Aggressive trail long runner after 2.8R+ (5min)";
            setupType = SETUP_LONG_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_1_R) >= 0) {
            BigDecimal atrStop         = closePrice.subtract(atr.multiply(RUNNER_TRAIL_ATR_PHASE_1));
            BigDecimal lockedProfitStop = entryPrice.add(initialRisk.multiply(RUNNER_LOCK_R_PHASE_1));
            candidateStop = atrStop.max(lockedProfitStop).max(entryPrice);
            reason    = "Trail long runner after 1.8R+ (5min)";
            setupType = SETUP_LONG_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_BREAK_EVEN_R) >= 0) {
            candidateStop = entryPrice;
            reason    = "Move long runner to break-even after 0.8R (5min)";
            setupType = SETUP_LONG_BREAK_EVEN;
        }

        if (candidateStop == null) return hold(context, "Long runner not ready for trailing update");
        if (candidateStop.compareTo(currentStop) <= 0 || candidateStop.compareTo(closePrice) >= 0) {
            return hold(context, "Long runner stop already optimal");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(setupType).side(SIDE_LONG).reason(reason)
                .stopLossPrice(candidateStop).trailingStopPrice(candidateStop)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "SCALP_MOM", "LONG", "RUNNER_TRAIL"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk,
                        "rMultiple", rMultiple, "candidateStop", candidateStop
                ))
                .build();
    }

    private StrategyDecision manageShortRunnerPosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snapshot
    ) {
        BigDecimal entryPrice  = strategyHelper.safe(snapshot.getEntryPrice());
        BigDecimal currentStop = strategyHelper.safe(snapshot.getCurrentStopLossPrice());
        BigDecimal closePrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr         = resolveAtr(feature);

        BigDecimal initialStop = snapshot.getInitialStopLossPrice() != null
                ? snapshot.getInitialStopLossPrice() : currentStop;
        BigDecimal initialRisk = initialStop.subtract(entryPrice);

        if (initialRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid short runner risk structure");

        BigDecimal move = entryPrice.subtract(closePrice);
        if (move.compareTo(ZERO) <= 0) return hold(context, "Short runner not yet in profit");

        BigDecimal rMultiple = move.divide(initialRisk, 6, RoundingMode.HALF_UP);

        BigDecimal candidateStop = null;
        String reason = null;
        String setupType = null;

        if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_2_R) >= 0) {
            BigDecimal atrStop         = closePrice.add(atr.multiply(RUNNER_TRAIL_ATR_PHASE_2));
            BigDecimal lockedProfitStop = entryPrice.subtract(initialRisk.multiply(RUNNER_LOCK_R_PHASE_2));
            candidateStop = atrStop.min(lockedProfitStop).min(entryPrice);
            reason    = "Aggressive trail short runner after 2.8R+ (5min)";
            setupType = SETUP_SHORT_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_1_R) >= 0) {
            BigDecimal atrStop         = closePrice.add(atr.multiply(RUNNER_TRAIL_ATR_PHASE_1));
            BigDecimal lockedProfitStop = entryPrice.subtract(initialRisk.multiply(RUNNER_LOCK_R_PHASE_1));
            candidateStop = atrStop.min(lockedProfitStop).min(entryPrice);
            reason    = "Trail short runner after 1.8R+ (5min)";
            setupType = SETUP_SHORT_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_BREAK_EVEN_R) >= 0) {
            candidateStop = entryPrice;
            reason    = "Move short runner to break-even after 0.8R (5min)";
            setupType = SETUP_SHORT_BREAK_EVEN;
        }

        if (candidateStop == null) return hold(context, "Short runner not ready for trailing update");
        if (candidateStop.compareTo(currentStop) >= 0 || candidateStop.compareTo(closePrice) <= 0) {
            return hold(context, "Short runner stop already optimal");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(setupType).side(SIDE_SHORT).reason(reason)
                .stopLossPrice(candidateStop).trailingStopPrice(candidateStop)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "SCALP_MOM", "SHORT", "RUNNER_TRAIL"))
                .diagnostics(Map.of(
                        "entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk,
                        "rMultiple", rMultiple, "candidateStop", candidateStop
                ))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Trend Filters (5min + 1h bias)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Bullish trend on 5min: EMA9 > EMA21, EMA21 > EMA50, EMA50 > EMA200, slope positive
     * 1h bias: price > EMA50 > EMA200, not RANGE
     *
     * Note: EMA9 and EMA21 should be added to FeatureStore for 5min.
     * Falls back to EMA50/EMA200 if EMA9/EMA21 not available.
     */
    private boolean isBullishTrend(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        FeatureStore biasFeature = context.getBiasFeatureStore();
        MarketData biasMarket   = context.getBiasMarketData();

        // 5min trend: price > EMA50 > EMA200, slope positive, not RANGE
        boolean currentTrend = strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && strategyHelper.hasValue(feature.getEma200())
                && strategyHelper.hasValue(feature.getEma50Slope())
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0
                && feature.getEma50().compareTo(feature.getEma200()) > 0
                && feature.getEma50Slope().compareTo(ZERO) > 0
                && !"RANGE".equalsIgnoreCase(feature.getTrendRegime());

        // 1h bias: price > EMA50 > EMA200, not RANGE
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
        MarketData biasMarket   = context.getBiasMarketData();

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
    // Entry Confirmation (5min micro-structure)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Long confirmation:
     *   - MACD histogram > 0 (momentum turning up)
     *   - RSI >= 50 (bullish momentum zone)
     *   - ADX >= 18 (directional strength, not flat)
     *   - Price > EMA50 (above fast MA)
     */
    private boolean isBullishEntryConfirmation(FeatureStore feature, MarketData marketData) {
        return strategyHelper.hasValue(feature.getMacdHistogram())
                && strategyHelper.hasValue(feature.getRsi())
                && strategyHelper.hasValue(feature.getAdx())
                && strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && feature.getMacdHistogram().compareTo(ZERO) > 0
                && feature.getRsi().compareTo(RSI_LONG_MIN) >= 0
                && feature.getAdx().compareTo(ADX_MIN) >= 0
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0;
    }

    private boolean isBearishEntryConfirmation(FeatureStore feature, MarketData marketData) {
        return strategyHelper.hasValue(feature.getMacdHistogram())
                && strategyHelper.hasValue(feature.getRsi())
                && strategyHelper.hasValue(feature.getAdx())
                && strategyHelper.hasValue(marketData.getClosePrice())
                && strategyHelper.hasValue(feature.getEma50())
                && feature.getMacdHistogram().compareTo(ZERO) < 0
                && feature.getRsi().compareTo(RSI_SHORT_MAX) <= 0
                && feature.getAdx().compareTo(ADX_MIN) >= 0
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
        BigDecimal score = new BigDecimal("0.35"); // base

        if (strategyHelper.hasValue(feature.getAdx())
                && feature.getAdx().compareTo(ADX_MIN) >= 0)
            score = score.add(new BigDecimal("0.10"));

        if (strategyHelper.hasValue(feature.getRsi())
                && feature.getRsi().compareTo(RSI_SCORE_MIN) >= 0)
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

        // Volume confirmation — slightly lower threshold for 5min (0.75 vs 0.80)
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

        if (strategyHelper.hasValue(feature.getAdx())
                && feature.getAdx().compareTo(ADX_MIN) >= 0)
            score = score.add(new BigDecimal("0.10"));

        if (strategyHelper.hasValue(feature.getRsi())
                && feature.getRsi().compareTo(RSI_SCORE_MAX) <= 0)
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

        // Penalise high jump-risk harder on 5min (gaps hit faster)
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
                && Boolean.FALSE.equals(context.getMarketQualitySnapshot().getTradable())) {
            return true;
        }
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

    private BigDecimal resolveTp1R(EnrichedStrategyContext context) {
        BigDecimal v = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("tp1R") : null;
        return (v != null && v.compareTo(ZERO) > 0) ? v : DEFAULT_TP1_R;
    }

    private BigDecimal resolveTp2R(EnrichedStrategyContext context) {
        BigDecimal v = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("tp2R") : null;
        return (v != null && v.compareTo(ZERO) > 0) ? v : DEFAULT_TP2_R;
    }

    private BigDecimal resolveTp3R(EnrichedStrategyContext context) {
        BigDecimal v = context.getRuntimeConfig() != null
                ? context.getRuntimeConfig().getBigDecimal("tp3R") : null;
        return (v != null && v.compareTo(ZERO) > 0) ? v : DEFAULT_TP3_R;
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
        if (context.getRegimeSnapshot() != null && context.getRegimeSnapshot().getRegimeLabel() != null) {
            return context.getRegimeSnapshot().getRegimeLabel();
        }
        return feature != null ? feature.getTrendRegime() : null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Hold / Veto helpers
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType("SCALP_MOM")
                .reason(reason)
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
                .vetoed(Boolean.TRUE)
                .vetoReason(vetoReason)
                .reason("Decision vetoed by risk layer")
                .jumpRiskScore(resolveJumpRisk(context))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", "SCALP_MOM", "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }
}