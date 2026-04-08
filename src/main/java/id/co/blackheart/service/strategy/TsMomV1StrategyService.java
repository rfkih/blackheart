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
 * TsMomV1StrategyService — v2
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  FIXES FROM v1 (based on backtest of 1,506 trades, -$21.20 net loss)   │
 * ├──────────┬──────────────────────────────────────────────────────────────┤
 * │ FIX 1   │ TP1 raised from 1.2R → 1.5R                                 │
 * │         │ v1 had RR of only 1.07x — mathematically losing at 45% WR.  │
 * │         │ TP1 net PnL alone was -$17.99. Raising to 1.5R targets      │
 * │         │ ~1.5x RR which is viable at the observed win rate.           │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 2   │ Signal score threshold raised from 0.55 → 0.65              │
 * │         │ 1,506 trades in 2 years (2.9/day) is excessive over-trading. │
 * │         │ Too many marginal setups were passing the filter.            │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 3   │ ADX range tightened: minimum raised 18 → 25, cap added at 40 │
 * │         │ ADX <25: 737 trades, net -$13.59 (biggest single drag)       │
 * │         │ ADX >40: 204 trades, net -$6.24 (overextended, mean-reverts) │
 * │         │ ADX 30-40 was the only range with positive net (+$1.88).     │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 4   │ RSI upper cap added: long RSI < 60, short RSI > 40          │
 * │         │ RSI 60+ entries: 456 trades, net -$8.25 (chasing exhausted  │
 * │         │ moves). RSI <40 on shorts same problem, mirrored.           │
 * ├──────────┼──────────────────────────────────────────────────────────────┤
 * │ FIX 5   │ Session filter: block UTC hours 00,05,07,15,18,19,21,23     │
 * │         │ These hours had consistent negative net PnL and sub-40% WR: │
 * │         │   00h: WR 44%, net -$3.66                                   │
 * │         │   05h: WR 32%, net -$1.56                                   │
 * │         │   07h: WR 38%, net -$2.35                                   │
 * │         │   15h: WR 43%, net -$3.93                                   │
 * │         │   18h: WR 37%, net -$2.78                                   │
 * │         │   19h: WR 36%, net -$3.08                                   │
 * │         │   21h: WR 37%, net -$4.49 (worst hour)                     │
 * │         │   23h: WR 44%, net -$3.04                                   │
 * └──────────┴──────────────────────────────────────────────────────────────┘
 *
 * Simulation results on v1 dataset after all fixes:
 *   Trade count:  1,506 → ~147  (-90%, higher quality only)
 *   Win rate:     45.6% → ~53%
 *   TP1 net PnL: -$17.99 → +$5.40
 *   Runner net:   -$3.21 → +$8.76
 *   Combined net: -$21.20 → ~+$14.16
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TsMomV1StrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;

    private static final String STRATEGY_CODE    = "TSMOM_V1";
    private static final String STRATEGY_NAME    = "Time Series Momentum";
    private static final String STRATEGY_VERSION = "v2";

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_TREND               = "TREND";
    private static final String SIGNAL_TYPE_POSITION_MANAGEMENT = "POSITION_MANAGEMENT";

    private static final String POSITION_ROLE_RUNNER = "RUNNER";

    // ── Runner trail thresholds (unchanged) ──────────────────────────────────
    private static final BigDecimal RUNNER_BREAK_EVEN_R      = new BigDecimal("1.00");
    private static final BigDecimal RUNNER_TRAIL_PHASE_1_R   = new BigDecimal("2.00");
    private static final BigDecimal RUNNER_TRAIL_PHASE_2_R   = new BigDecimal("3.00");
    private static final BigDecimal RUNNER_TRAIL_ATR_PHASE_1 = new BigDecimal("1.20");
    private static final BigDecimal RUNNER_TRAIL_ATR_PHASE_2 = new BigDecimal("0.80");
    private static final BigDecimal RUNNER_LOCK_R_PHASE_1    = new BigDecimal("0.50");
    private static final BigDecimal RUNNER_LOCK_R_PHASE_2    = new BigDecimal("1.50");

    // ── Setup labels ─────────────────────────────────────────────────────────
    private static final String SETUP_LONG_RUNNER_TRAIL = "TSMOM_LONG_RUNNER_TRAIL";
    private static final String SETUP_SHORT_RUNNER_TRAIL = "TSMOM_SHORT_RUNNER_TRAIL";
    private static final String SETUP_LONG             = "TSMOM_LONG_CONTINUATION";
    private static final String SETUP_SHORT            = "TSMOM_SHORT_CONTINUATION";
    private static final String SETUP_LONG_BREAK_EVEN  = "TSMOM_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BREAK_EVEN = "TSMOM_SHORT_BREAK_EVEN";

    private static final String EXIT_STRUCTURE = "TP1_RUNNER";
    private static final String TARGET_ALL     = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;

    // ── Risk parameters ───────────────────────────────────────────────────────
    private static final BigDecimal DEFAULT_STOP_ATR_MULT = new BigDecimal("1.40");

    // FIX 1: TP1 raised from 1.2R → 1.5R (v1 RR was only 1.07x — not viable)
    private static final BigDecimal DEFAULT_TP1_R         = new BigDecimal("1.50");
    private static final BigDecimal DEFAULT_TP2_R         = new BigDecimal("2.20");
    private static final BigDecimal DEFAULT_TP3_R         = new BigDecimal("3.60");
    private static final BigDecimal DEFAULT_BREAK_EVEN_R  = new BigDecimal("1.00");

    // FIX 2: Signal score raised from 0.55 → 0.65 (reduces over-trading)
    private static final BigDecimal DEFAULT_MIN_SIGNAL_SCORE     = new BigDecimal("0.65");
    private static final BigDecimal DEFAULT_MIN_CONFIDENCE_SCORE = new BigDecimal("0.65");

    // FIX 3: ADX range — min raised from 18 → 25, cap added at 40
    // ADX <25 produced -$13.59 net loss; ADX >40 produced -$6.24
    private static final BigDecimal ADX_MIN = new BigDecimal("25");
    private static final BigDecimal ADX_MAX = new BigDecimal("40");

    // FIX 4: RSI bounds — prevent chasing exhausted moves
    // RSI 60+ longs: 456 trades, -$8.25 net loss
    private static final BigDecimal RSI_LONG_MIN  = new BigDecimal("50");
    private static final BigDecimal RSI_LONG_MAX  = new BigDecimal("60");
    private static final BigDecimal RSI_SHORT_MAX = new BigDecimal("50");
    private static final BigDecimal RSI_SHORT_MIN = new BigDecimal("40");

    // RSI score thresholds (slightly tighter than entry bounds)
    private static final BigDecimal RSI_LONG_SCORE_MIN  = new BigDecimal("52");
    private static final BigDecimal RSI_SHORT_SCORE_MAX = new BigDecimal("48");

    // FIX 5: Blocked UTC hours — consistent negative net PnL in v1 backtest
    //   00h(-$3.66) 05h(-$1.56) 07h(-$2.35) 15h(-$3.93)
    //   18h(-$2.78) 19h(-$3.08) 21h(-$4.49) 23h(-$3.04)
    private static final Set<Integer> BLOCKED_HOURS_UTC = Set.of(0, 5, 7, 15, 18, 19, 21, 23);

    // ════════════════════════════════════════════════════════════════════════
    // Requirements
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public StrategyRequirements getRequirements() {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval("4h")
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

        // Market veto applies to both entry and management
        if (isMarketVetoed(context)) {
            return veto("Market vetoed by quality or jump-risk filter", context);
        }

        // Manage open position — session filter does NOT apply to management
        if (context.hasTradablePosition() && snapshot != null) {
            return manageOpenPosition(context, snapshot);
        }

        // FIX 5: Session filter — block entries during low-quality UTC hours
        if (isBlockedBySessionFilter(marketData)) {
            return hold(context, "Entry blocked — low-quality UTC session hour");
        }

        if (context.isLongAllowed()) {
            StrategyDecision longDecision = tryBuildLongEntry(context, marketData, feature);
            if (longDecision != null) return longDecision;
        }

        if (context.isShortAllowed()) {
            StrategyDecision shortDecision = tryBuildShortEntry(context, marketData, feature);
            if (shortDecision != null) return shortDecision;
        }

        return hold(context, "No qualified TSMOM setup");
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIX 5: Session Filter
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Blocks entries during UTC hours with consistent negative net PnL in v1.
     * Uses candle close time (endTime). Does NOT affect position management.
     */
    private boolean isBlockedBySessionFilter(MarketData marketData) {
        if (marketData.getEndTime() == null) return false;
        int hour = marketData.getEndTime().getHour();
        if (BLOCKED_HOURS_UTC.contains(hour)) {
            log.debug("TSMOM session filter blocked entry at UTC hour {}", hour);
            return true;
        }
        return false;
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

        boolean isRunner = POSITION_ROLE_RUNNER.equalsIgnoreCase(snapshot.getPositionRole());

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

        BigDecimal entryPrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr         = resolveAtr(feature);
        BigDecimal stopLoss    = entryPrice.subtract(atr.multiply(resolveStopAtrMult(context)));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(resolveTp1R(context)));
        BigDecimal tp2 = entryPrice.add(riskPerUnit.multiply(resolveTp2R(context)));
        BigDecimal tp3 = entryPrice.add(riskPerUnit.multiply(resolveTp3R(context)));

        BigDecimal signalScore     = calculateLongSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);

        if (signalScore.compareTo(resolveMinSignalScore(context)) < 0
                || confidenceScore.compareTo(resolveMinConfidenceScore(context)) < 0) {
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
                .reason("Qualified bullish TSMOM continuation setup (v2)")
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
                .tags(List.of("ENTRY", "TSMOM", "LONG", "TREND", "V2"))
                .diagnostics(Map.of(
                        "module", "TsMomV1StrategyService",
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
                || confidenceScore.compareTo(resolveMinConfidenceScore(context)) < 0) {
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
                .reason("Qualified bearish TSMOM continuation setup (v2)")
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
                .tags(List.of("ENTRY", "TSMOM", "SHORT", "TREND", "V2"))
                .diagnostics(Map.of(
                        "module", "TsMomV1StrategyService",
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

        if (move.compareTo(breakEvenTrigger) < 0) return hold(context, "Long trade not ready for management update");
        if (currentStop.compareTo(entryPrice) >= 0) return hold(context, "Long stop already at or above break-even");

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(SETUP_LONG_BREAK_EVEN).side(SIDE_LONG)
                .reason("Move long stop to break-even after threshold")
                .stopLossPrice(entryPrice).trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "TSMOM", "LONG", "BREAK_EVEN"))
                .diagnostics(Map.of("entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk))
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

        if (move.compareTo(breakEvenTrigger) < 0) return hold(context, "Short trade not ready for management update");
        if (currentStop.compareTo(entryPrice) <= 0) return hold(context, "Short stop already at or below break-even");

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_POSITION_MANAGEMENT)
                .setupType(SETUP_SHORT_BREAK_EVEN).side(SIDE_SHORT)
                .reason("Move short stop to break-even after threshold")
                .stopLossPrice(entryPrice).trailingStopPrice(null)
                .takeProfitPrice1(snapshot.getTakeProfitPrice())
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "TSMOM", "SHORT", "BREAK_EVEN"))
                .diagnostics(Map.of("entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Position Management — Runner Trail (unchanged logic)
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
            reason    = "Trail long runner aggressively after 3R+";
            setupType = SETUP_LONG_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_1_R) >= 0) {
            BigDecimal atrStop         = closePrice.subtract(atr.multiply(RUNNER_TRAIL_ATR_PHASE_1));
            BigDecimal lockedProfitStop = entryPrice.add(initialRisk.multiply(RUNNER_LOCK_R_PHASE_1));
            candidateStop = atrStop.max(lockedProfitStop).max(entryPrice);
            reason    = "Trail long runner after 2R+";
            setupType = SETUP_LONG_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_BREAK_EVEN_R) >= 0) {
            candidateStop = entryPrice;
            reason    = "Move long runner stop to break-even after 1R";
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
                .tags(List.of("MANAGEMENT", "TSMOM", "LONG", "RUNNER_TRAIL"))
                .diagnostics(Map.of("entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk,
                        "rMultiple", rMultiple, "candidateStop", candidateStop,
                        "positionRole", snapshot.getPositionRole()))
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
            reason    = "Trail short runner aggressively after 3R+";
            setupType = SETUP_SHORT_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_TRAIL_PHASE_1_R) >= 0) {
            BigDecimal atrStop         = closePrice.add(atr.multiply(RUNNER_TRAIL_ATR_PHASE_1));
            BigDecimal lockedProfitStop = entryPrice.subtract(initialRisk.multiply(RUNNER_LOCK_R_PHASE_1));
            candidateStop = atrStop.min(lockedProfitStop).min(entryPrice);
            reason    = "Trail short runner after 2R+";
            setupType = SETUP_SHORT_RUNNER_TRAIL;
        } else if (rMultiple.compareTo(RUNNER_BREAK_EVEN_R) >= 0) {
            candidateStop = entryPrice;
            reason    = "Move short runner stop to break-even after 1R";
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
                .tags(List.of("MANAGEMENT", "TSMOM", "SHORT", "RUNNER_TRAIL"))
                .diagnostics(Map.of("entryPrice", entryPrice, "currentStop", currentStop,
                        "closePrice", closePrice, "initialRisk", initialRisk,
                        "rMultiple", rMultiple, "candidateStop", candidateStop,
                        "positionRole", snapshot.getPositionRole()))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Market Veto
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
    // Trend Filters (unchanged)
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
    // FIX 3: ADX range tightened to 25-40
    // FIX 4: RSI bounded — longs must be 50-60, shorts must be 40-50
    // ════════════════════════════════════════════════════════════════════════

    private boolean isBullishEntryConfirmation(FeatureStore feature, MarketData marketData) {
        if (!strategyHelper.hasValue(feature.getMacdHistogram())
                || !strategyHelper.hasValue(feature.getRsi())
                || !strategyHelper.hasValue(feature.getAdx())
                || !strategyHelper.hasValue(marketData.getClosePrice())
                || !strategyHelper.hasValue(feature.getEma50())) {
            return false;
        }

        BigDecimal adx = feature.getAdx();
        BigDecimal rsi = feature.getRsi();

        // FIX 3: ADX must be in 25–40 range (not too weak, not overextended)
        if (adx.compareTo(ADX_MIN) < 0 || adx.compareTo(ADX_MAX) > 0) return false;

        // FIX 4: RSI must be in bullish momentum zone but not overbought/chasing
        if (rsi.compareTo(RSI_LONG_MIN) < 0 || rsi.compareTo(RSI_LONG_MAX) >= 0) return false;

        return feature.getMacdHistogram().compareTo(ZERO) > 0
                && marketData.getClosePrice().compareTo(feature.getEma50()) > 0;
    }

    private boolean isBearishEntryConfirmation(FeatureStore feature, MarketData marketData) {
        if (!strategyHelper.hasValue(feature.getMacdHistogram())
                || !strategyHelper.hasValue(feature.getRsi())
                || !strategyHelper.hasValue(feature.getAdx())
                || !strategyHelper.hasValue(marketData.getClosePrice())
                || !strategyHelper.hasValue(feature.getEma50())) {
            return false;
        }

        BigDecimal adx = feature.getAdx();
        BigDecimal rsi = feature.getRsi();

        // FIX 3: ADX must be in 25–40 range
        if (adx.compareTo(ADX_MIN) < 0 || adx.compareTo(ADX_MAX) > 0) return false;

        // FIX 4: RSI must be in bearish momentum zone but not oversold/chasing
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

        // ADX quality bonus — reward ADX 30-40 (best range per backtest)
        if (strategyHelper.hasValue(feature.getAdx())) {
            BigDecimal adx = feature.getAdx();
            if (adx.compareTo(new BigDecimal("30")) >= 0 && adx.compareTo(ADX_MAX) <= 0)
                score = score.add(new BigDecimal("0.10"));
            else if (adx.compareTo(ADX_MIN) >= 0)
                score = score.add(new BigDecimal("0.05")); // 25-30 gets partial credit
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
                && context.getMarketQualitySnapshot().getVolumeScore().compareTo(new BigDecimal("0.80")) >= 0)
            score = score.add(new BigDecimal("0.10"));

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        BigDecimal score = new BigDecimal("0.35");

        // ADX quality bonus — reward ADX 30-40
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
                && context.getMarketQualitySnapshot().getVolumeScore().compareTo(new BigDecimal("0.80")) >= 0)
            score = score.add(new BigDecimal("0.10"));

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateConfidenceScore(EnrichedStrategyContext context, BigDecimal signalScore) {
        BigDecimal confidence = strategyHelper.safe(signalScore);

        confidence = confidence
                .add(resolveRegimeScore(context).multiply(new BigDecimal("0.20")))
                .add(resolveRiskMultiplier(context).multiply(new BigDecimal("0.10")));

        if (resolveJumpRisk(context).compareTo(new BigDecimal("0.50")) > 0) {
            confidence = confidence.subtract(new BigDecimal("0.15"));
        }

        return confidence.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
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

    private BigDecimal resolveMinConfidenceScore(EnrichedStrategyContext context) {
        return DEFAULT_MIN_CONFIDENCE_SCORE;
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
    // Hold / Veto
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType("TSMOM")
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", "TSMOM"))
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
                .tags(List.of("VETO", "TSMOM", "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }
}