package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.RiskSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.strategy.VolatilitySnapshot;
import id.co.blackheart.dto.vcb.VcbParams;
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
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class VcbStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;
    private final VcbStrategyParamService vcbStrategyParamService;

    // ── Identity ──────────────────────────────────────────────────────────────
    private static final String STRATEGY_CODE    = "VCB";
    private static final String STRATEGY_NAME    = "Volatility Compression Breakout";
    private static final String STRATEGY_VERSION = "v2_1_profitability_balance";

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_BREAKOUT   = "COMPRESSION_BREAKOUT";
    private static final String SIGNAL_TYPE_MANAGEMENT = "POSITION_MANAGEMENT";
    private static final String POSITION_ROLE_RUNNER   = "RUNNER";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;

    private static final String SETUP_LONG_BREAKOUT  = "VCB_LONG_BREAKOUT";
    private static final String SETUP_SHORT_BREAKOUT = "VCB_SHORT_BREAKOUT";
    private static final String SETUP_LONG_RUNNER    = "VCB_LONG_RUNNER_TRAIL";
    private static final String SETUP_SHORT_RUNNER   = "VCB_SHORT_RUNNER_TRAIL";
    private static final String SETUP_LONG_BE        = "VCB_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BE       = "VCB_SHORT_BREAK_EVEN";

    private static final String EXIT_STRUCTURE = "TP1_RUNNER";
    private static final String TARGET_ALL     = "ALL";

    private static final String DIAG_ENTRY    = "entry";
    private static final String DIAG_CUR_STOP = "curStop";
    private static final String DIAG_CLOSE    = "close";
    private static final String TAG_MANAGEMENT = "MANAGEMENT";
    private static final String ATR_LOCK_PHRASE = " ATR, lock ";

    @Override
    public StrategyRequirements getRequirements() {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval("4h")
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
            return hold(context, "Invalid context or missing data");
        }

        UUID accountStrategyId = context.getAccountStrategy() != null
                ? context.getAccountStrategy().getAccountStrategyId() : null;
        VcbParams p = vcbStrategyParamService.getParams(accountStrategyId);

        MarketData marketData = context.getMarketData();
        FeatureStore feature = context.getFeatureStore();
        PositionSnapshot snap = context.getPositionSnapshot();

        BigDecimal close = strategyHelper.safe(marketData.getClosePrice());
        if (close.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid close price");
        }

        if (isMarketVetoed(context)) {
            logVeto(context, marketData);
            return veto("Market vetoed by quality / jump-risk filter", context);
        }

        if (context.hasTradablePosition() && snap != null) {
            return managePosition(context, marketData, feature, snap, p);
        }

        logEvaluation(context, marketData);

        if (context.isLongAllowed()) {
            StrategyDecision d = tryLongEntry(context, marketData, feature, p);
            if (d != null) return d;
        }

        if (context.isShortAllowed()) {
            StrategyDecision d = tryShortEntry(context, marketData, feature, p);
            if (d != null) return d;
        }

        return hold(context, "No qualified VCB setup");
    }

    private void logVeto(EnrichedStrategyContext context, MarketData marketData) {
        Object tradable = context.getMarketQualitySnapshot() != null
                ? context.getMarketQualitySnapshot().getTradable() : "null";
        log.info("VCB VETOED time={} tradable={} jumpRisk={}",
                marketData.getEndTime(), tradable, resolveJumpRisk(context));
    }

    private void logEvaluation(EnrichedStrategyContext context, MarketData marketData) {
        String prevFs = context.getPreviousFeatureStore() != null ? "present" : "NULL";
        log.info("VCB evaluate time={} longAllowed={} shortAllowed={} prevFs={}",
                marketData.getEndTime(), context.isLongAllowed(), context.isShortAllowed(), prevFs);
    }

    /** Resolved entry sizing for a single OPEN-side decision. Shared between
     *  long and short — only the math differs (stop above/below the bar). */
    private record EntrySizing(BigDecimal entry, BigDecimal stop,
                               BigDecimal riskPerUnit, BigDecimal tp1) {}

    private StrategyDecision tryLongEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            VcbParams p
    ) {
        if (!passesLongGates(context, marketData, feature, p)) return null;

        EntrySizing sizing = computeLongSizing(marketData, feature, p);
        if (sizing == null) return null;

        BigDecimal signalScore = calculateLongSignalScore(context, feature, p);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);
        if (signalScore.compareTo(p.getMinSignalScore()) < 0
                || confidenceScore.compareTo(p.getMinSignalScore()) < 0) {
            log.info("VCB LONG score FAIL time={} signal={} confidence={} min={}",
                    marketData.getEndTime(), signalScore, confidenceScore, p.getMinSignalScore());
            return null;
        }

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_LONG);
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Long notional size is zero");
        }

        log.info("VCB LONG ENTRY | time={} close={} stop={} tp1={} risk%={} score={}",
                marketData.getEndTime(), sizing.entry(), sizing.stop(), sizing.tp1(),
                sizing.riskPerUnit().divide(sizing.entry(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")),
                signalScore);

        return buildLongOpenDecision(context, feature, sizing, signalScore, confidenceScore, notionalSize);
    }

    private boolean passesLongGates(EnrichedStrategyContext context, MarketData marketData,
                                    FeatureStore feature, VcbParams p) {
        String trendRegime = feature.getTrendRegime();
        if (trendRegime != null && !"BULL".equalsIgnoreCase(trendRegime)) {
            log.info("VCB LONG gate1a FAIL [regime not BULL] time={} regime={}", marketData.getEndTime(), trendRegime);
            return false;
        }
        if (feature.getAdx() != null && feature.getAdx().compareTo(p.getAdxEntryMax()) >= 0) {
            log.info("VCB LONG gate1b FAIL [ADX too high] time={} adx={}", marketData.getEndTime(), feature.getAdx());
            return false;
        }
        if (!passesLongDiSpread(marketData, feature, p)) return false;
        if (feature.getRsi() != null && feature.getRsi().compareTo(p.getLongRsiMin()) < 0) {
            log.info("VCB LONG gate1d FAIL [RSI too weak] time={} rsi={}", marketData.getEndTime(), feature.getRsi());
            return false;
        }
        FeatureStore prevFeature = context.getPreviousFeatureStore();
        if (!passesCompressionGate(SIDE_LONG, marketData, feature, prevFeature, p)) return false;
        if (!isBullishBreakoutCandle(feature, prevFeature, marketData, p)) {
            log.info("VCB LONG gate3 FAIL [breakout candle] time={} close={} prevDonchianUp={} body={} vol={} rsi={}",
                    marketData.getEndTime(), marketData.getClosePrice(),
                    prevFeature != null ? prevFeature.getDonchianUpper20() : "n/a",
                    feature.getBodyToRangeRatio(), feature.getRelativeVolume20(), feature.getRsi());
            return false;
        }
        return true;
    }

    private boolean passesLongDiSpread(MarketData marketData, FeatureStore feature, VcbParams p) {
        if (feature.getPlusDI() == null || feature.getMinusDI() == null) return true;
        BigDecimal diSpread = feature.getPlusDI().subtract(feature.getMinusDI());
        if (diSpread.compareTo(p.getLongDiSpreadMin()) < 0) {
            log.info("VCB LONG gate1c FAIL [DI spread too weak] time={} +DI={} -DI={} spread={}",
                    marketData.getEndTime(), feature.getPlusDI(), feature.getMinusDI(), diSpread);
            return false;
        }
        return true;
    }

    /** Compression gate is identical between long/short — both require either
     *  the current or prior bar to be in a compression regime. */
    private boolean passesCompressionGate(String side, MarketData marketData, FeatureStore feature,
                                          FeatureStore prevFeature, VcbParams p) {
        boolean currCompressed = isCompressionActive(feature, p);
        boolean prevCompressed = prevFeature != null && isCompressionActive(prevFeature, p);
        if (currCompressed || prevCompressed) return true;
        log.info("VCB {} gate2 FAIL [compression] time={} curr[bbW={} kcW={} atrR={} er={}] prev[bbW={} kcW={} atrR={} er={}]",
                side, marketData.getEndTime(),
                feature.getBbWidth(), feature.getKcWidth(), feature.getAtrRatio(), feature.getEfficiencyRatio20(),
                prevFeature != null ? prevFeature.getBbWidth() : "n/a",
                prevFeature != null ? prevFeature.getKcWidth() : "n/a",
                prevFeature != null ? prevFeature.getAtrRatio() : "n/a",
                prevFeature != null ? prevFeature.getEfficiencyRatio20() : "n/a");
        return false;
    }

    private EntrySizing computeLongSizing(MarketData marketData, FeatureStore feature, VcbParams p) {
        BigDecimal entryPrice = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr = resolveAtr(feature);
        BigDecimal stopLoss = strategyHelper.safe(marketData.getLowPrice())
                .subtract(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal maxAllowedRisk = entryPrice.multiply(p.getMaxEntryRiskPct());
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) {
            log.debug("VCB long skipped — stop too wide: risk={}% entry={}",
                    riskPerUnit.divide(entryPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")),
                    entryPrice);
            return null;
        }
        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(p.getTp1R()));
        return new EntrySizing(entryPrice, stopLoss, riskPerUnit, tp1);
    }

    private StrategyDecision buildLongOpenDecision(EnrichedStrategyContext context, FeatureStore feature,
                                                   EntrySizing sizing, BigDecimal signalScore,
                                                   BigDecimal confidenceScore, BigDecimal notionalSize) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_BREAKOUT)
                .setupType(SETUP_LONG_BREAKOUT)
                .side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("VCB long: compression squeeze broken upward with high-quality breakout confirmation")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(sizing.stop())
                .trailingStopPrice(null)
                .takeProfitPrice1(sizing.tp1())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature != null ? feature.getAdx() : null)
                .entryAtr(feature != null ? feature.getAtr() : null)
                .entryRsi(feature != null ? feature.getRsi() : null)
                .entryTrendRegime(feature != null ? feature.getTrendRegime() : null)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "VCB", SIDE_LONG, "BREAKOUT", "V2_1_BALANCE"))
                .diagnostics(buildDiagnostics(sizing.entry(), sizing.stop(), sizing.tp1()))
                .build();
    }

    private StrategyDecision tryShortEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            VcbParams p
    ) {
        if (!passesShortGates(context, marketData, feature, p)) return null;

        EntrySizing sizing = computeShortSizing(marketData, feature, p);
        if (sizing == null) return null;

        BigDecimal signalScore = calculateShortSignalScore(context, feature, p);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);
        if (signalScore.compareTo(p.getMinSignalScore()) < 0
                || confidenceScore.compareTo(p.getMinSignalScore()) < 0) {
            log.info("VCB SHORT score FAIL time={} signal={} confidence={} min={}",
                    marketData.getEndTime(), signalScore, confidenceScore, p.getMinSignalScore());
            return null;
        }

        // SHORT spot orders sell base currency (BTC); executor compares against
        // BTC balance. Use the BTC-denominated helper, not the USDT one.
        BigDecimal positionSize = strategyHelper.calculateShortPositionSize(context);
        if (positionSize.compareTo(ZERO) <= 0) {
            return hold(context, "Short position size is zero");
        }

        log.info("VCB SHORT ENTRY | time={} close={} stop={} tp1={} score={}",
                marketData.getEndTime(), sizing.entry(), sizing.stop(), sizing.tp1(), signalScore);

        return buildShortOpenDecision(context, feature, sizing, signalScore, confidenceScore, positionSize);
    }

    private boolean passesShortGates(EnrichedStrategyContext context, MarketData marketData,
                                     FeatureStore feature, VcbParams p) {
        String trendRegime = feature.getTrendRegime();
        if (trendRegime != null && !"BEAR".equalsIgnoreCase(trendRegime)) {
            log.info("VCB SHORT gate1a FAIL [regime not BEAR] time={} regime={}", marketData.getEndTime(), trendRegime);
            return false;
        }
        if (feature.getAdx() != null && feature.getAdx().compareTo(p.getAdxEntryMax()) >= 0) {
            log.info("VCB SHORT gate1b FAIL [ADX too high] time={} adx={}", marketData.getEndTime(), feature.getAdx());
            return false;
        }
        if (!passesShortDiSpread(marketData, feature, p)) return false;
        if (feature.getRsi() != null && feature.getRsi().compareTo(p.getShortRsiMax()) > 0) {
            log.info("VCB SHORT gate1d FAIL [RSI too strong for short] time={} rsi={}", marketData.getEndTime(), feature.getRsi());
            return false;
        }
        FeatureStore prevFeature = context.getPreviousFeatureStore();
        if (!passesCompressionGate(SIDE_SHORT, marketData, feature, prevFeature, p)) return false;
        if (!isBearishBreakoutCandle(feature, prevFeature, marketData, p)) {
            log.info("VCB SHORT gate3 FAIL [breakout candle] time={} close={} prevDonchianLow={} body={} vol={} rsi={}",
                    marketData.getEndTime(), marketData.getClosePrice(),
                    prevFeature != null ? prevFeature.getDonchianLower20() : "n/a",
                    feature.getBodyToRangeRatio(), feature.getRelativeVolume20(), feature.getRsi());
            return false;
        }
        return true;
    }

    private boolean passesShortDiSpread(MarketData marketData, FeatureStore feature, VcbParams p) {
        if (feature.getPlusDI() == null || feature.getMinusDI() == null) return true;
        BigDecimal diSpread = feature.getMinusDI().subtract(feature.getPlusDI());
        if (diSpread.compareTo(p.getShortDiSpreadMin()) < 0) {
            log.info("VCB SHORT gate1c FAIL [DI spread too weak] time={} +DI={} -DI={} spread={}",
                    marketData.getEndTime(), feature.getPlusDI(), feature.getMinusDI(), diSpread);
            return false;
        }
        return true;
    }

    private EntrySizing computeShortSizing(MarketData marketData, FeatureStore feature, VcbParams p) {
        BigDecimal entryPrice = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr = resolveAtr(feature);
        BigDecimal stopLoss = strategyHelper.safe(marketData.getHighPrice())
                .add(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = stopLoss.subtract(entryPrice);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal maxAllowedRisk = entryPrice.multiply(p.getMaxEntryRiskPct());
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) {
            log.debug("VCB short skipped — stop too wide");
            return null;
        }
        BigDecimal tp1 = entryPrice.subtract(riskPerUnit.multiply(p.getTp1R()));
        return new EntrySizing(entryPrice, stopLoss, riskPerUnit, tp1);
    }

    private StrategyDecision buildShortOpenDecision(EnrichedStrategyContext context, FeatureStore feature,
                                                    EntrySizing sizing, BigDecimal signalScore,
                                                    BigDecimal confidenceScore, BigDecimal positionSize) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_BREAKOUT)
                .setupType(SETUP_SHORT_BREAKOUT)
                .side(SIDE_SHORT)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("VCB short: compression squeeze broken downward with high-quality breakout confirmation")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .positionSize(positionSize)
                .stopLossPrice(sizing.stop())
                .trailingStopPrice(null)
                .takeProfitPrice1(sizing.tp1())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature != null ? feature.getAdx() : null)
                .entryAtr(feature != null ? feature.getAtr() : null)
                .entryRsi(feature != null ? feature.getRsi() : null)
                .entryTrendRegime(feature != null ? feature.getTrendRegime() : null)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "VCB", SIDE_SHORT, "BREAKOUT", "V2_1_BALANCE"))
                .diagnostics(buildDiagnostics(sizing.entry(), sizing.stop(), sizing.tp1()))
                .build();
    }

    private StrategyDecision managePosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snap,
            VcbParams p
    ) {
        if (snap.getSide() == null || snap.getEntryPrice() == null || snap.getCurrentStopLossPrice() == null) {
            return hold(context, "Position management: incomplete snapshot");
        }

        boolean isRunner = POSITION_ROLE_RUNNER.equalsIgnoreCase(snap.getPositionRole());

        if (SIDE_LONG.equalsIgnoreCase(snap.getSide())) {
            return isRunner
                    ? manageRunnerLong(context, marketData, feature, snap, p)
                    : manageStandardLong(context, marketData, snap, p);
        }
        if (SIDE_SHORT.equalsIgnoreCase(snap.getSide())) {
            return isRunner
                    ? manageRunnerShort(context, marketData, feature, snap, p)
                    : manageStandardShort(context, marketData, snap, p);
        }

        return hold(context, "Position management: unknown side");
    }

    private StrategyDecision manageStandardLong(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snap,
            VcbParams p
    ) {
        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal risk = entry.subtract(curStop);

        if (risk.compareTo(ZERO) <= 0) return hold(context, "Invalid long risk");

        if (close.subtract(entry).compareTo(risk.multiply(p.getRunnerBreakEvenR())) < 0) {
            return hold(context, "Long not ready for BE");
        }
        if (curStop.compareTo(entry) >= 0) {
            return hold(context, "Long stop already at BE");
        }

        return buildManagementDecision(context, SIDE_LONG, SETUP_LONG_BE,
                entry, snap.getTakeProfitPrice(),
                "Move long TP1 leg to break-even after 1.5R",
                Map.of(DIAG_ENTRY, entry, DIAG_CUR_STOP, curStop, DIAG_CLOSE, close));
    }

    private StrategyDecision manageStandardShort(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snap,
            VcbParams p
    ) {
        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal risk = curStop.subtract(entry);

        if (risk.compareTo(ZERO) <= 0) return hold(context, "Invalid short risk");

        if (entry.subtract(close).compareTo(risk.multiply(p.getRunnerBreakEvenR())) < 0) {
            return hold(context, "Short not ready for BE");
        }
        if (curStop.compareTo(entry) <= 0) {
            return hold(context, "Short stop already at BE");
        }

        return buildManagementDecision(context, SIDE_SHORT, SETUP_SHORT_BE,
                entry, snap.getTakeProfitPrice(),
                "Move short TP1 leg to break-even after 1.5R",
                Map.of(DIAG_ENTRY, entry, DIAG_CUR_STOP, curStop, DIAG_CLOSE, close));
    }

    private StrategyDecision manageRunnerLong(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snap,
            VcbParams p
    ) {
        if (isBearish4HBias(context, p)) {
            log.info("VCB long runner exited — 4H bias reversed to bearish | close={}", marketData.getClosePrice());
            return StrategyDecision.builder()
                    .decisionType(DecisionType.CLOSE_LONG)
                    .strategyCode(STRATEGY_CODE)
                    .strategyName(STRATEGY_NAME)
                    .strategyVersion(STRATEGY_VERSION)
                    .strategyInterval(context.getInterval())
                    .signalType(SIGNAL_TYPE_MANAGEMENT)
                    .setupType(SETUP_LONG_RUNNER)
                    .side(SIDE_LONG)
                    .reason("VCB long runner: 4H bias reversed — macro structure no longer supports trade")
                    .targetPositionRole(POSITION_ROLE_RUNNER)
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of(TAG_MANAGEMENT, "VCB", SIDE_LONG, "REGIME_EXIT"))
                    .diagnostics(Map.of())
                    .build();
        }

        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr = resolveAtr(feature);

        BigDecimal initStop = snap.getInitialStopLossPrice() != null ? snap.getInitialStopLossPrice() : curStop;
        BigDecimal initRisk = entry.subtract(initStop);
        if (initRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid runner risk");

        BigDecimal move = close.subtract(entry);
        if (move.compareTo(ZERO) <= 0) return hold(context, "Runner not in profit");

        BigDecimal rMultiple = move.divide(initRisk, 6, RoundingMode.HALF_UP);

        BigDecimal candidate = null;
        String reason = null;
        String setup = null;

        if (rMultiple.compareTo(p.getRunnerPhase3R()) >= 0) {
            BigDecimal atrStop = close.subtract(atr.multiply(p.getRunnerAtrPhase3()));
            BigDecimal lockStop = entry.add(initRisk.multiply(p.getRunnerLockPhase3R()));
            candidate = atrStop.max(lockStop).max(entry);
            reason = "VCB runner phase 3: trail " + p.getRunnerAtrPhase3() + ATR_LOCK_PHRASE + p.getRunnerLockPhase3R() + "R";
            setup = SETUP_LONG_RUNNER;
        } else if (rMultiple.compareTo(p.getRunnerPhase2R()) >= 0) {
            BigDecimal atrStop = close.subtract(atr.multiply(p.getRunnerAtrPhase2()));
            BigDecimal lockStop = entry.add(initRisk.multiply(p.getRunnerLockPhase2R()));
            candidate = atrStop.max(lockStop).max(entry);
            reason = "VCB runner phase 2: trail " + p.getRunnerAtrPhase2() + ATR_LOCK_PHRASE + p.getRunnerLockPhase2R() + "R";
            setup = SETUP_LONG_RUNNER;
        } else if (rMultiple.compareTo(p.getRunnerBreakEvenR()) >= 0) {
            candidate = entry;
            reason = "VCB runner phase 1: move to break-even at " + p.getRunnerBreakEvenR() + "R";
            setup = SETUP_LONG_BE;
        } else if (rMultiple.compareTo(p.getRunnerHalfR()) >= 0) {
            BigDecimal halfLossStop = entry.subtract(initRisk.multiply(p.getRunnerHalfR()));
            if (halfLossStop.compareTo(curStop) > 0) {
                candidate = halfLossStop;
                reason = "VCB runner phase 0.9: lift stop to -" + p.getRunnerHalfR() + "R loss limiter";
                setup = SETUP_LONG_BE;
            }
        }

        if (candidate == null) return hold(context, "Runner not ready");
        if (candidate.compareTo(curStop) <= 0 || candidate.compareTo(close) >= 0) {
            return hold(context, "Runner stop already optimal");
        }

        return buildTrailDecision(context, SIDE_LONG, setup, candidate,
                snap.getTakeProfitPrice(), reason,
                Map.of("rMultiple", rMultiple, "candidate", candidate,
                        DIAG_CUR_STOP, curStop, DIAG_CLOSE, close, "atr", atr));
    }

    private StrategyDecision manageRunnerShort(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snap,
            VcbParams p
    ) {
        if (isBullish4HBias(context, p)) {
            log.info("VCB short runner exited — 4H bias reversed to bullish | close={}", marketData.getClosePrice());
            return StrategyDecision.builder()
                    .decisionType(DecisionType.CLOSE_SHORT)
                    .strategyCode(STRATEGY_CODE)
                    .strategyName(STRATEGY_NAME)
                    .strategyVersion(STRATEGY_VERSION)
                    .strategyInterval(context.getInterval())
                    .signalType(SIGNAL_TYPE_MANAGEMENT)
                    .setupType(SETUP_SHORT_RUNNER)
                    .side(SIDE_SHORT)
                    .reason("VCB short runner: 4H bias reversed — macro structure no longer supports trade")
                    .targetPositionRole(POSITION_ROLE_RUNNER)
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of(TAG_MANAGEMENT, "VCB", SIDE_SHORT, "REGIME_EXIT"))
                    .diagnostics(Map.of())
                    .build();
        }

        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr = resolveAtr(feature);

        BigDecimal initStop = snap.getInitialStopLossPrice() != null ? snap.getInitialStopLossPrice() : curStop;
        BigDecimal initRisk = initStop.subtract(entry);
        if (initRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid runner risk");

        BigDecimal move = entry.subtract(close);
        if (move.compareTo(ZERO) <= 0) return hold(context, "Runner not in profit");

        BigDecimal rMultiple = move.divide(initRisk, 6, RoundingMode.HALF_UP);

        BigDecimal candidate = null;
        String reason = null;
        String setup = null;

        if (rMultiple.compareTo(p.getRunnerPhase3R()) >= 0) {
            BigDecimal atrStop = close.add(atr.multiply(p.getRunnerAtrPhase3()));
            BigDecimal lockStop = entry.subtract(initRisk.multiply(p.getRunnerLockPhase3R()));
            candidate = atrStop.min(lockStop).min(entry);
            reason = "VCB short runner phase 3: trail " + p.getRunnerAtrPhase3() + ATR_LOCK_PHRASE + p.getRunnerLockPhase3R() + "R";
            setup = SETUP_SHORT_RUNNER;
        } else if (rMultiple.compareTo(p.getRunnerPhase2R()) >= 0) {
            BigDecimal atrStop = close.add(atr.multiply(p.getRunnerAtrPhase2()));
            BigDecimal lockStop = entry.subtract(initRisk.multiply(p.getRunnerLockPhase2R()));
            candidate = atrStop.min(lockStop).min(entry);
            reason = "VCB short runner phase 2: trail " + p.getRunnerAtrPhase2() + ATR_LOCK_PHRASE + p.getRunnerLockPhase2R() + "R";
            setup = SETUP_SHORT_RUNNER;
        } else if (rMultiple.compareTo(p.getRunnerBreakEvenR()) >= 0) {
            candidate = entry;
            reason = "VCB short runner phase 1: move to break-even at " + p.getRunnerBreakEvenR() + "R";
            setup = SETUP_SHORT_BE;
        } else if (rMultiple.compareTo(p.getRunnerHalfR()) >= 0) {
            BigDecimal halfLossStop = entry.add(initRisk.multiply(p.getRunnerHalfR()));
            if (halfLossStop.compareTo(curStop) < 0) {
                candidate = halfLossStop;
                reason = "VCB short runner phase 0.9: lift stop to -" + p.getRunnerHalfR() + "R loss limiter";
                setup = SETUP_SHORT_BE;
            }
        }

        if (candidate == null) return hold(context, "Short runner not ready");
        if (candidate.compareTo(curStop) >= 0 || candidate.compareTo(close) <= 0) {
            return hold(context, "Short runner stop already optimal");
        }

        return buildTrailDecision(context, SIDE_SHORT, setup, candidate,
                snap.getTakeProfitPrice(), reason,
                Map.of("rMultiple", rMultiple, "candidate", candidate,
                        DIAG_CUR_STOP, curStop, DIAG_CLOSE, close, "atr", atr));
    }

    private boolean isBullish4HBias(EnrichedStrategyContext context, VcbParams p) {
        FeatureStore bias = context.getBiasFeatureStore();
        MarketData biasData = context.getBiasMarketData();

        if (bias == null || biasData == null) {
            log.debug("VCB: no 4H bias data, rejecting long entry");
            return false;
        }

        boolean emaStructure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasData.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) > 0
                && biasData.getClosePrice().compareTo(bias.getEma200()) > 0;

        boolean erAligned = bias.getSignedEr20() == null
                || bias.getSignedEr20().compareTo(p.getBiasErMin()) > 0;

        log.debug("VCB 4H BULL bias: emaStructure={} erAligned={} | ema50={} ema200={} close={} slope={} signedEr20={}",
                emaStructure, erAligned,
                bias.getEma50(), bias.getEma200(),
                biasData.getClosePrice(), bias.getEma50Slope(), bias.getSignedEr20());

        return emaStructure && erAligned;
    }

    private boolean isBearish4HBias(EnrichedStrategyContext context, VcbParams p) {
        FeatureStore bias = context.getBiasFeatureStore();
        MarketData biasData = context.getBiasMarketData();

        if (bias == null || biasData == null) {
            log.debug("VCB: no 4H bias data, rejecting short entry");
            return false;
        }

        boolean emaStructure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasData.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) < 0
                && biasData.getClosePrice().compareTo(bias.getEma200()) < 0;

        boolean erAligned = bias.getSignedEr20() == null
                || bias.getSignedEr20().compareTo(p.getBiasErMin().negate()) < 0;

        return emaStructure && erAligned;
    }

    private boolean isCompressionActive(FeatureStore feature, VcbParams p) {
        boolean bbSqueeze = false;
        if (feature.getBbWidth() != null && feature.getKcWidth() != null
                && feature.getKcWidth().compareTo(ZERO) > 0) {
            BigDecimal kcThreshold = feature.getKcWidth().multiply(p.getSqueezeKcTolerance());
            bbSqueeze = feature.getBbWidth().compareTo(kcThreshold) < 0;
        }

        boolean atrCompressed = feature.getAtrRatio() != null
                && feature.getAtrRatio().compareTo(p.getAtrRatioCompressMax()) < 0;

        boolean erLow = feature.getEfficiencyRatio20() != null
                && feature.getEfficiencyRatio20().compareTo(p.getErCompressMax()) < 0;

        int conditionsMet = (bbSqueeze ? 1 : 0) + (atrCompressed ? 1 : 0) + (erLow ? 1 : 0);
        boolean result = conditionsMet >= 2;

        log.debug("VCB compression check: bbSqueeze={} atrCompressed={} erLow={} met={} -> {}",
                bbSqueeze, atrCompressed, erLow, conditionsMet, result);

        return result;
    }

    private boolean isBullishBreakoutCandle(FeatureStore feature, FeatureStore prevFeature, MarketData marketData, VcbParams p) {
        if (prevFeature == null || !strategyHelper.hasValue(prevFeature.getDonchianUpper20())
                || !strategyHelper.hasValue(marketData.getClosePrice())) {
            return false;
        }

        boolean abovePrevDonchian = marketData.getClosePrice()
                .compareTo(prevFeature.getDonchianUpper20()) > 0;

        boolean convictionCandle = strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(p.getBodyRatioBreakoutMin()) >= 0;

        boolean volumeConfirmed = isVolumeInBreakoutBand(feature, p);

        return abovePrevDonchian && convictionCandle && volumeConfirmed;
    }

    private boolean isBearishBreakoutCandle(FeatureStore feature, FeatureStore prevFeature, MarketData marketData, VcbParams p) {
        if (prevFeature == null || !strategyHelper.hasValue(prevFeature.getDonchianLower20())
                || !strategyHelper.hasValue(marketData.getClosePrice())) {
            return false;
        }

        boolean belowPrevDonchian = marketData.getClosePrice()
                .compareTo(prevFeature.getDonchianLower20()) < 0;

        boolean convictionCandle = strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(p.getBodyRatioBreakoutMin()) >= 0;

        boolean volumeConfirmed = isVolumeInBreakoutBand(feature, p);

        return belowPrevDonchian && convictionCandle && volumeConfirmed;
    }

    private boolean isVolumeInBreakoutBand(FeatureStore feature, VcbParams p) {
        if (!strategyHelper.hasValue(feature.getRelativeVolume20())) {
            return true;
        }
        BigDecimal relVol = feature.getRelativeVolume20();
        return relVol.compareTo(p.getRelVolBreakoutMin()) >= 0
                && relVol.compareTo(p.getRelVolBreakoutMax()) <= 0;
    }

    // ── Scoring (internal weights — not user-configurable) ────────────────────

    private BigDecimal calculateLongSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            VcbParams p
    ) {
        BigDecimal score = new BigDecimal("0.35");

        if (isBullish4HBias(context, p)) score = score.add(new BigDecimal("0.15"));

        score = score.add(scoreCompressionDepth(context.getPreviousFeatureStore()));

        if (strategyHelper.hasValue(feature.getRelativeVolume20())) {
            BigDecimal relVol = feature.getRelativeVolume20();
            if (relVol.compareTo(new BigDecimal("1.40")) >= 0 && relVol.compareTo(new BigDecimal("2.20")) <= 0) {
                score = score.add(new BigDecimal("0.15"));
            }
        }

        score = score.add(scoreCandleQuality(feature));

        if (strategyHelper.hasValue(feature.getRsi()) && feature.getRsi().compareTo(new BigDecimal("68")) >= 0) {
            score = score.add(new BigDecimal("0.05"));
        }

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            VcbParams p
    ) {
        BigDecimal score = new BigDecimal("0.35");

        if (isBearish4HBias(context, p)) score = score.add(new BigDecimal("0.15"));

        score = score.add(scoreCompressionDepth(context.getPreviousFeatureStore()));

        if (strategyHelper.hasValue(feature.getRelativeVolume20())) {
            BigDecimal relVol = feature.getRelativeVolume20();
            if (relVol.compareTo(new BigDecimal("1.40")) >= 0 && relVol.compareTo(new BigDecimal("2.20")) <= 0) {
                score = score.add(new BigDecimal("0.15"));
            }
        }

        score = score.add(scoreCandleQuality(feature));

        if (strategyHelper.hasValue(feature.getRsi()) && feature.getRsi().compareTo(new BigDecimal("32")) <= 0) {
            score = score.add(new BigDecimal("0.05"));
        }

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    /** Bonus for prior-bar squeeze depth (BB inside KC) and ATR compression.
     *  Pulled out of the per-side score methods so they stay under Sonar's
     *  cognitive-complexity ceiling without changing the additive components. */
    private BigDecimal scoreCompressionDepth(FeatureStore prevFeature) {
        if (prevFeature == null) return ZERO;
        BigDecimal bonus = ZERO;
        if (prevFeature.getBbWidth() != null
                && prevFeature.getKcWidth() != null
                && prevFeature.getKcWidth().compareTo(ZERO) > 0) {
            BigDecimal ratio = prevFeature.getBbWidth().divide(prevFeature.getKcWidth(), 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(new BigDecimal("0.70")) < 0) bonus = bonus.add(new BigDecimal("0.10"));
        }
        if (prevFeature.getAtrRatio() != null
                && prevFeature.getAtrRatio().compareTo(new BigDecimal("0.90")) < 0) {
            bonus = bonus.add(new BigDecimal("0.10"));
        }
        return bonus;
    }

    /** Body-ratio + mid-band-ADX bonuses applied to both long and short. */
    private BigDecimal scoreCandleQuality(FeatureStore feature) {
        BigDecimal bonus = ZERO;
        if (strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(new BigDecimal("0.65")) >= 0) {
            bonus = bonus.add(new BigDecimal("0.10"));
        }
        if (strategyHelper.hasValue(feature.getAdx())
                && feature.getAdx().compareTo(new BigDecimal("12")) >= 0
                && feature.getAdx().compareTo(new BigDecimal("25")) < 0) {
            bonus = bonus.add(new BigDecimal("0.10"));
        }
        return bonus;
    }

    private BigDecimal calculateConfidenceScore(EnrichedStrategyContext context, BigDecimal signalScore) {
        BigDecimal confidence = strategyHelper.safe(signalScore);
        confidence = confidence.add(resolveRegimeScore(context).multiply(new BigDecimal("0.10")));
        if (resolveJumpRisk(context).compareTo(new BigDecimal("0.50")) > 0) {
            confidence = confidence.subtract(new BigDecimal("0.15"));
        }
        return confidence.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private StrategyDecision buildManagementDecision(
            EnrichedStrategyContext context, String side, String setupType,
            BigDecimal newStop, BigDecimal takeProfit, String reason, Map<String, Object> diag) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(setupType)
                .side(side)
                .reason(reason)
                .stopLossPrice(newStop)
                .trailingStopPrice(null)
                .takeProfitPrice1(takeProfit)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of(TAG_MANAGEMENT, "VCB", side, "BREAK_EVEN"))
                .diagnostics(diag)
                .build();
    }

    private StrategyDecision buildTrailDecision(
            EnrichedStrategyContext context, String side, String setupType,
            BigDecimal newStop, BigDecimal takeProfit, String reason, Map<String, Object> diag) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(setupType)
                .side(side)
                .reason(reason)
                .stopLossPrice(newStop)
                .trailingStopPrice(newStop)
                .takeProfitPrice1(takeProfit)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of(TAG_MANAGEMENT, "VCB", side, "RUNNER_TRAIL"))
                .diagnostics(diag)
                .build();
    }

    private BigDecimal resolveAtr(FeatureStore feature) {
        return (feature != null && feature.getAtr() != null && feature.getAtr().compareTo(ZERO) > 0)
                ? feature.getAtr() : ONE;
    }

    private BigDecimal resolveRegimeScore(EnrichedStrategyContext context) {
        RegimeSnapshot r = context.getRegimeSnapshot();
        return (r != null && r.getTrendScore() != null) ? r.getTrendScore() : ZERO;
    }

    private BigDecimal resolveJumpRisk(EnrichedStrategyContext context) {
        VolatilitySnapshot v = context.getVolatilitySnapshot();
        return (v != null && v.getJumpRiskScore() != null) ? v.getJumpRiskScore() : ZERO;
    }

    private BigDecimal resolveRiskMultiplier(EnrichedStrategyContext context) {
        RiskSnapshot r = context.getRiskSnapshot();
        return (r != null && r.getRiskMultiplier() != null) ? r.getRiskMultiplier() : ONE;
    }

    private String resolveRegimeLabel(EnrichedStrategyContext context, FeatureStore feature) {
        if (context.getRegimeSnapshot() != null && context.getRegimeSnapshot().getRegimeLabel() != null) {
            return context.getRegimeSnapshot().getRegimeLabel();
        }
        return feature != null ? feature.getTrendRegime() : null;
    }

    private Map<String, Object> buildDiagnostics(
             BigDecimal entry, BigDecimal stop,
            BigDecimal tp1) {
        return Map.of("module", "VcbStrategyService", "entryPrice", entry, "stopLoss", stop, "tp1", tp1);
    }

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType(SIGNAL_TYPE_BREAKOUT)
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", "VCB"))
                .build();
    }

    private StrategyDecision veto(String vetoReason, EnrichedStrategyContext context) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .vetoed(Boolean.TRUE)
                .vetoReason(vetoReason)
                .reason("VCB vetoed by risk layer")
                .jumpRiskScore(context != null ? resolveJumpRisk(context) : ZERO)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", "VCB", "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }
}
