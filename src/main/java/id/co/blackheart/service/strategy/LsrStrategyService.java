package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.lsr.LsrParams;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LsrStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;
    private final LsrStrategyParamService lsrStrategyParamService;

    // ─────────────────────────────────────────────────────────────────────────
    // Identity (not user-configurable)
    // ─────────────────────────────────────────────────────────────────────────
    private static final String STRATEGY_CODE = "LSR";
    private static final String STRATEGY_NAME = "Liquidity Sweep Reversal Adaptive";
    private static final String STRATEGY_VERSION = "v5";

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_SWEEP = "LIQUIDITY_SWEEP_REVERSAL";
    private static final String SIGNAL_TYPE_MANAGEMENT = "POSITION_MANAGEMENT";

    private static final String SETUP_LONG_SWEEP = "LSR_V5_LONG_SWEEP_RECLAIM";
    private static final String SETUP_LONG_CONTINUATION = "LSR_V5_LONG_CONTINUATION_RECLAIM";
    private static final String SETUP_SHORT_EXHAUSTION = "LSR_V5_SHORT_EXHAUSTION_PREMIUM";

    private static final String SETUP_LONG_BE = "LSR_V5_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BE = "LSR_V5_SHORT_BREAK_EVEN";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String TARGET_ALL = "ALL";

    private static final String TAG_LSR_V5 = "LSR_V5";
    private static final String TAG_ENTRY = "ENTRY";
    private static final String TAG_MANAGEMENT = "MANAGEMENT";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    // ─────────────────────────────────────────────────────────────────────────
    // All tunable parameters are now resolved per-account-strategy via
    // LsrStrategyParamService (Redis-cached, DB-backed, defaults built-in).
    // ─────────────────────────────────────────────────────────────────────────

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
            return hold(context, "Invalid context or missing market data");
        }

        UUID accountStrategyId = context.getAccountStrategy() != null
                ? context.getAccountStrategy().getAccountStrategyId() : null;
        LsrParams p = lsrStrategyParamService.getParams(accountStrategyId);

        MarketData md = context.getMarketData();
        FeatureStore fs = context.getFeatureStore();

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid close price");
        }

        if (isMarketVetoed(context)) {
            return veto("Market vetoed by quality / jump-risk filter", context);
        }

        if (isAtrSpikeVetoed(fs, p)) {
            return veto("ATR spike veto — exhaustion or chaotic volatility regime", context);
        }

        if (isAdxOutsideActiveZone(fs, p)) {
            return hold(context, "ADX outside active zone");
        }

        PositionSnapshot snap = context.getPositionSnapshot();
        if (context.hasTradablePosition() && snap != null) {
            return managePosition(context, md, fs, snap, p);
        }

        if (context.getPreviousFeatureStore() == null) {
            return hold(context, "Previous FeatureStore unavailable");
        }

        FeatureStore prevFs = context.getPreviousFeatureStore();
        RegimeState regime = classifyRegime(context, fs, p);

        if (regime == RegimeState.COMPRESSION
                || regime == RegimeState.EXHAUSTION_SPIKE
                || regime == RegimeState.CHAOTIC_TREND) {
            return hold(context, "Regime veto: " + regime.name());
        }

        StrategyDecision entry = tryEntries(context, md, fs, prevFs, regime, p);
        return entry != null ? entry : hold(context, "No qualified LSR adaptive v5 setup");
    }

    private StrategyDecision tryEntries(EnrichedStrategyContext context, MarketData md, FeatureStore fs,
                                        FeatureStore prevFs, RegimeState regime, LsrParams p) {
        if ((regime == RegimeState.BULL_TREND
                || regime == RegimeState.RANGING
                || regime == RegimeState.NEUTRAL)
                && context.isLongAllowed()) {
            StrategyDecision sweepLong = tryLongSweepReclaimEntry(context, md, fs, prevFs, regime, p);
            if (sweepLong != null) return sweepLong;

            StrategyDecision contLong = tryLongContinuationReclaimEntry(context, md, fs, prevFs, regime, p);
            if (contLong != null) return contLong;
        }

        if ((regime == RegimeState.BEAR_TREND || regime == RegimeState.RANGING)
                && context.isShortAllowed()) {
            return tryShortExhaustionEntry(context, md, fs, prevFs, regime, p);
        }

        return null;
    }


    @SuppressWarnings("java:S3776") // Frozen LSR regime classifier per CLAUDE.md — branch sequence is intentional.
    private RegimeState classifyRegime(EnrichedStrategyContext context, FeatureStore fs, LsrParams p) {
        if (strategyHelper.hasValue(fs.getAtrRatio())
                && fs.getAtrRatio().compareTo(p.getAtrRatioExhaustion()) >= 0) {
            return RegimeState.EXHAUSTION_SPIKE;
        }

        FeatureStore bias = context.getBiasFeatureStore();
        MarketData biasData = context.getBiasMarketData();

        if (bias == null || biasData == null) {
            return RegimeState.NEUTRAL;
        }

        BigDecimal adx4h = strategyHelper.hasValue(bias.getAdx()) ? bias.getAdx() : ZERO;

        if (adx4h.compareTo(p.getAdxTrendingMin()) >= 0
                && strategyHelper.hasValue(fs.getAtrRatio())
                && fs.getAtrRatio().compareTo(p.getAtrRatioChaotic()) >= 0) {
            return RegimeState.CHAOTIC_TREND;
        }

        if (adx4h.compareTo(p.getAdxTrendingMin()) >= 0
                && strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasData.getClosePrice())) {

            if (bias.getEma50().compareTo(bias.getEma200()) > 0
                    && biasData.getClosePrice().compareTo(bias.getEma200()) > 0) {
                return RegimeState.BULL_TREND;
            }

            if (bias.getEma50().compareTo(bias.getEma200()) < 0
                    && biasData.getClosePrice().compareTo(bias.getEma200()) < 0) {
                return RegimeState.BEAR_TREND;
            }
        }

        if (adx4h.compareTo(p.getAdxCompressionMax()) < 0
                && strategyHelper.hasValue(fs.getAtrRatio())
                && fs.getAtrRatio().compareTo(p.getAtrRatioCompress()) < 0) {
            return RegimeState.COMPRESSION;
        }

        if (adx4h.compareTo(new BigDecimal("20")) <= 0) {
            return RegimeState.RANGING;
        }

        return RegimeState.NEUTRAL;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Setup A: Long sweep reclaim
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("java:S3776") // Frozen LSR entry logic per CLAUDE.md — guard sequence is intentional.
    private StrategyDecision tryLongSweepReclaimEntry(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            FeatureStore prevFs,
            RegimeState regime,
            LsrParams p
    ) {
        if (!strategyHelper.hasValue(prevFs.getDonchianLower20())) return null;
        if (!passesLongSweepFilters(fs, p)) return null;

        BigDecimal sweepLevel = prevFs.getDonchianLower20();
        BigDecimal low = strategyHelper.safe(md.getLowPrice());
        BigDecimal open = strategyHelper.safe(md.getOpenPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal atr = resolveAtr(fs);

        boolean swept = low.compareTo(sweepLevel) < 0;
        boolean reclaim = close.compareTo(sweepLevel) > 0;
        boolean bullishCandle = close.compareTo(open) > 0;

        if (!swept || !reclaim || !bullishCandle) return null;

        BigDecimal sweepDist = sweepLevel.subtract(low);
        if (sweepDist.compareTo(atr.multiply(p.getLongSweepMinAtr())) < 0
                || sweepDist.compareTo(atr.multiply(p.getLongSweepMaxAtr())) > 0) {
            return null;
        }

        if (regime == RegimeState.RANGING
                && strategyHelper.hasValue(fs.getDonchianMid20())
                && close.compareTo(fs.getDonchianMid20()) > 0) {
            return null;
        }

        BigDecimal entryPrice = close;
        BigDecimal stopLoss = low.subtract(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);

        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entryPrice.multiply(p.getMaxRiskPct())) > 0) return null;

        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(p.getTp1RLongSweep()));

        BigDecimal signalScore = calculateLongSweepScore(fs, sweepDist, atr, regime);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore, false);

        if (signalScore.compareTo(p.getMinSignalScoreLongSweep()) < 0
                || confidenceScore.compareTo(p.getMinConfidenceScoreLongSweep()) < 0) {
            return null;
        }

        FeatureStore biasFs = context.getBiasFeatureStore();
        if (biasFs != null && "NEUTRAL".equalsIgnoreCase(biasFs.getTrendRegime())) {
            return null;
        }

        BigDecimal notionalSize = strategyHelper.calculateLongEntryNotional(context, entryPrice, stopLoss);
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Long sweep notional size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_SWEEP)
                .setupType(SETUP_LONG_SWEEP)
                .side(SIDE_LONG)
                .regimeLabel(regime.name())
                .reason("Long sweep reclaim: pullback sweep below prior Donchian low reclaimed")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_SINGLE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(fs.getAdx())
                .entryAtr(fs.getAtr())
                .entryRsi(fs.getRsi())
                .entryTrendRegime(regime.name())
                .decisionTime(LocalDateTime.now())
                .tags(List.of(TAG_ENTRY, TAG_LSR_V5, SIDE_LONG, "SWEEP_RECLAIM", regime.name()))
                .diagnostics(buildDiagnostics(entryPrice, SIDE_LONG, SETUP_LONG_SWEEP))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup B: Long continuation reclaim
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("java:S3776") // Frozen LSR entry logic per CLAUDE.md — guard sequence is intentional.
    private StrategyDecision tryLongContinuationReclaimEntry(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            FeatureStore prevFs,
            RegimeState regime,
            LsrParams p
    ) {
        if (!strategyHelper.hasValue(prevFs.getDonchianLower20())) return null;
        if (!passesLongContinuationFilters(fs, p)) return null;

        BigDecimal donchianLower = prevFs.getDonchianLower20();
        BigDecimal low = strategyHelper.safe(md.getLowPrice());
        BigDecimal open = strategyHelper.safe(md.getOpenPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal atr = resolveAtr(fs);

        BigDecimal lowerBuffer = donchianLower.add(atr.multiply(p.getLongContDonchianBufferAtr()));

        boolean nearLowerBoundary = low.compareTo(lowerBuffer) <= 0;
        boolean reclaim = close.compareTo(donchianLower) >= 0;
        boolean bullishCandle = close.compareTo(open) > 0;

        if (!nearLowerBoundary || !reclaim || !bullishCandle) return null;

        if (low.compareTo(donchianLower) < 0) {
            return null;
        }

        if (regime == RegimeState.RANGING
                && strategyHelper.hasValue(fs.getDonchianMid20())
                && close.compareTo(fs.getDonchianMid20()) > 0) {
            return null;
        }

        BigDecimal entryPrice = close;
        BigDecimal stopLoss = low.subtract(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);

        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entryPrice.multiply(p.getMaxRiskPct())) > 0) return null;

        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(p.getTp1RLongContinuation()));

        BigDecimal signalScore = calculateLongContinuationScore(fs, regime);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore, false);

        if (signalScore.compareTo(p.getMinSignalScoreLongCont()) < 0
                || confidenceScore.compareTo(p.getMinConfidenceScoreLongCont()) < 0) {
            return null;
        }

        FeatureStore biasFsCont = context.getBiasFeatureStore();
        if (biasFsCont != null && "NEUTRAL".equalsIgnoreCase(biasFsCont.getTrendRegime())) {
            return null;
        }

        BigDecimal baseNotional = strategyHelper.calculateLongEntryNotional(context, entryPrice, stopLoss);
        BigDecimal notionalSize = baseNotional.multiply(p.getLongContinuationNotionalMultiplier()).setScale(8, RoundingMode.HALF_UP);

        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Long continuation notional size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_SWEEP)
                .setupType(SETUP_LONG_CONTINUATION)
                .side(SIDE_LONG)
                .regimeLabel(regime.name())
                .reason("Long continuation reclaim: shallow pullback near Donchian lower reclaimed")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_SINGLE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(fs.getAdx())
                .entryAtr(fs.getAtr())
                .entryRsi(fs.getRsi())
                .entryTrendRegime(regime.name())
                .decisionTime(LocalDateTime.now())
                .tags(List.of(TAG_ENTRY, TAG_LSR_V5, SIDE_LONG, "CONTINUATION_RECLAIM", regime.name()))
                .diagnostics(buildDiagnostics(entryPrice, SIDE_LONG, SETUP_LONG_CONTINUATION))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup C: Premium short exhaustion
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("java:S3776") // Frozen LSR entry logic per CLAUDE.md — guard sequence is intentional.
    private StrategyDecision tryShortExhaustionEntry(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            FeatureStore prevFs,
            RegimeState regime,
            LsrParams p
    ) {
        if (!strategyHelper.hasValue(prevFs.getDonchianUpper20())) return null;
        if (!passesShortPremiumFilters(fs, p)) return null;

        BigDecimal sweepLevel = prevFs.getDonchianUpper20();
        BigDecimal high = strategyHelper.safe(md.getHighPrice());
        BigDecimal open = strategyHelper.safe(md.getOpenPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal atr = resolveAtr(fs);

        boolean swept = high.compareTo(sweepLevel) > 0;
        boolean reject = close.compareTo(sweepLevel) < 0;
        boolean bearishCandle = close.compareTo(open) < 0;

        if (!swept || !reject || !bearishCandle) return null;

        BigDecimal sweepDist = high.subtract(sweepLevel);
        if (sweepDist.compareTo(atr.multiply(p.getShortSweepMinAtr())) < 0
                || sweepDist.compareTo(atr.multiply(p.getShortSweepMaxAtr())) > 0) {
            return null;
        }

        if (regime == RegimeState.RANGING
                && strategyHelper.hasValue(fs.getDonchianMid20())
                && close.compareTo(fs.getDonchianMid20()) < 0) {
            return null;
        }

        BigDecimal entryPrice = close;
        BigDecimal stopLoss = high.add(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = stopLoss.subtract(entryPrice);

        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entryPrice.multiply(p.getMaxRiskPct())) > 0) return null;

        BigDecimal tp1 = entryPrice.subtract(riskPerUnit.multiply(p.getTp1RShort()));

        BigDecimal signalScore = calculateShortPremiumScore(fs, sweepDist, atr, regime);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore, true);

        if (signalScore.compareTo(p.getMinSignalScoreShort()) < 0
                || confidenceScore.compareTo(p.getMinSignalScoreShort()) < 0) {
            return null;
        }

        // SHORT entries on Binance spot sell base currency (BTC) — the executor
        // reads `positionSize` and matches against the BTC balance. The
        // BTC-denominated helper handles both legacy direct-allocation and the
        // V55 risk-based path (BTC qty derived from cash × riskPct / stopDist,
        // capped by asset inventory).
        BigDecimal basePositionSize = strategyHelper.calculateShortEntryQty(context, entryPrice, stopLoss);
        BigDecimal positionSize = basePositionSize.multiply(p.getShortNotionalMultiplier()).setScale(8, RoundingMode.HALF_UP);

        if (positionSize.compareTo(ZERO) <= 0) {
            return hold(context, "Short premium position size is zero");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_SWEEP)
                .setupType(SETUP_SHORT_EXHAUSTION)
                .side(SIDE_SHORT)
                .regimeLabel(regime.name())
                .reason("Premium short exhaustion: only overextended rejection shorts")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .positionSize(positionSize)
                .stopLossPrice(stopLoss)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_SINGLE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(fs.getAdx())
                .entryAtr(fs.getAtr())
                .entryRsi(fs.getRsi())
                .entryTrendRegime(regime.name())
                .decisionTime(LocalDateTime.now())
                .tags(List.of(TAG_ENTRY, TAG_LSR_V5, SIDE_SHORT, "PREMIUM_EXHAUSTION", regime.name()))
                .diagnostics(buildDiagnostics(entryPrice, SIDE_SHORT, SETUP_SHORT_EXHAUSTION))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Position management
    // ─────────────────────────────────────────────────────────────────────────
    private StrategyDecision managePosition(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            PositionSnapshot snap,
            LsrParams p
    ) {
        if (snap.getSide() == null || snap.getEntryPrice() == null || snap.getCurrentStopLossPrice() == null) {
            return hold(context, "Position management incomplete");
        }

        String setupType = inferSetupType(snap);

        if (SIDE_LONG.equalsIgnoreCase(snap.getSide())) {
            return manageLongPosition(context, md, fs, snap, setupType, p);
        }

        if (SIDE_SHORT.equalsIgnoreCase(snap.getSide())) {
            return manageShortPosition(context, md, snap, p);
        }

        return hold(context, "Unknown position side");
    }

    @SuppressWarnings("java:S3776") // Frozen LSR exit logic per CLAUDE.md — break-even/time-stop sequence is intentional.
    private StrategyDecision manageLongPosition(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            PositionSnapshot snap,
            String setupType,
            LsrParams p
    ) {
        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal risk = entry.subtract(curStop);

        if (risk.compareTo(ZERO) <= 0) return hold(context, "Invalid long risk");

        boolean continuation = SETUP_LONG_CONTINUATION.equals(setupType);

        int timeStopBars = continuation ? p.getTimeStopBarsLongContinuation() : p.getTimeStopBarsLongSweep();
        BigDecimal timeStopMinR = continuation ? p.getTimeStopMinRLongContinuation() : p.getTimeStopMinRLongSweep();
        BigDecimal beTriggerR = continuation ? p.getBeTriggerRLongContinuation() : p.getBeTriggerRLongSweep();

        // In the best ADX pocket, give longs a bit more room.
        if (strategyHelper.hasValue(fs.getAdx())
                && fs.getAdx().compareTo(new BigDecimal("25")) >= 0
                && fs.getAdx().compareTo(new BigDecimal("30")) <= 0) {
            timeStopBars += 2;
        }

        long bars = computeBarsInTrade(snap, md, context.getInterval());
        if (bars >= timeStopBars) {
            BigDecimal profitR = close.subtract(entry).divide(risk, 6, RoundingMode.HALF_UP);
            if (profitR.compareTo(timeStopMinR) < 0) {
                return StrategyDecision.builder()
                        .decisionType(DecisionType.CLOSE_LONG)
                        .strategyCode(STRATEGY_CODE)
                        .strategyName(STRATEGY_NAME)
                        .strategyVersion(STRATEGY_VERSION)
                        .strategyInterval(context.getInterval())
                        .signalType(SIGNAL_TYPE_MANAGEMENT)
                        .setupType(SETUP_LONG_BE)
                        .side(SIDE_LONG)
                        .reason("Long time stop")
                        .targetPositionRole(TARGET_ALL)
                        .decisionTime(LocalDateTime.now())
                        .tags(List.of(TAG_MANAGEMENT, TAG_LSR_V5, SIDE_LONG, "TIME_STOP"))
                        .build();
            }
        }

        BigDecimal threshold = risk.multiply(beTriggerR);
        if (close.subtract(entry).compareTo(threshold) < 0) {
            return hold(context, "Long BE not triggered");
        }
        BigDecimal longBeStop = entry.add(risk.multiply(p.getBeFeeBufferR()));
        if (curStop.compareTo(longBeStop) >= 0) {
            return hold(context, "Long BE already set");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(SETUP_LONG_BE)
                .side(SIDE_LONG)
                .reason("Move long stop to BE+fee buffer")
                .stopLossPrice(longBeStop)
                .takeProfitPrice1(snap.getTakeProfitPrice())
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of(TAG_MANAGEMENT, TAG_LSR_V5, SIDE_LONG, "BREAK_EVEN"))
                .build();
    }

    private StrategyDecision manageShortPosition(
            EnrichedStrategyContext context,
            MarketData md,
            PositionSnapshot snap,
            LsrParams p
    ) {
        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal risk = curStop.subtract(entry);

        if (risk.compareTo(ZERO) <= 0) return hold(context, "Invalid short risk");

        long bars = computeBarsInTrade(snap, md, context.getInterval());
        if (bars >= p.getTimeStopBarsShort()) {
            BigDecimal profitR = entry.subtract(close).divide(risk, 6, RoundingMode.HALF_UP);
            if (profitR.compareTo(p.getTimeStopMinRShort()) < 0) {
                return StrategyDecision.builder()
                        .decisionType(DecisionType.CLOSE_SHORT)
                        .strategyCode(STRATEGY_CODE)
                        .strategyName(STRATEGY_NAME)
                        .strategyVersion(STRATEGY_VERSION)
                        .strategyInterval(context.getInterval())
                        .signalType(SIGNAL_TYPE_MANAGEMENT)
                        .setupType(SETUP_SHORT_BE)
                        .side(SIDE_SHORT)
                        .reason("Short time stop")
                        .targetPositionRole(TARGET_ALL)
                        .decisionTime(LocalDateTime.now())
                        .tags(List.of(TAG_MANAGEMENT, TAG_LSR_V5, SIDE_SHORT, "TIME_STOP"))
                        .build();
            }
        }

        BigDecimal threshold = risk.multiply(p.getBeTriggerRShort());
        if (entry.subtract(close).compareTo(threshold) < 0) {
            return hold(context, "Short BE not triggered");
        }
        BigDecimal shortBeStop = entry.subtract(risk.multiply(p.getBeFeeBufferR()));
        if (curStop.compareTo(shortBeStop) <= 0) {
            return hold(context, "Short BE already set");
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(SETUP_SHORT_BE)
                .side(SIDE_SHORT)
                .reason("Move short stop to BE+fee buffer")
                .stopLossPrice(shortBeStop)
                .takeProfitPrice1(snap.getTakeProfitPrice())
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of(TAG_MANAGEMENT, TAG_LSR_V5, SIDE_SHORT, "BREAK_EVEN"))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoring (not user-configurable — internal strategy logic)
    // ─────────────────────────────────────────────────────────────────────────
    private BigDecimal calculateLongSweepScore(
            FeatureStore fs,
            BigDecimal sweepDist,
            BigDecimal atr,
            RegimeState regime
    ) {
        BigDecimal score = new BigDecimal("0.35");

        if (regime == RegimeState.BULL_TREND) score = score.add(new BigDecimal("0.10"));
        if (regime == RegimeState.NEUTRAL) score = score.subtract(new BigDecimal("0.02"));

        if (strategyHelper.hasValue(fs.getRelativeVolume20()) && fs.getRelativeVolume20().compareTo(new BigDecimal("1.05")) >= 0) {
            score = score.add(new BigDecimal("0.06"));
        }
        if (strategyHelper.hasValue(fs.getBodyToRangeRatio()) && fs.getBodyToRangeRatio().compareTo(new BigDecimal("0.35")) >= 0) {
            score = score.add(new BigDecimal("0.06"));
        }
        if (strategyHelper.hasValue(fs.getCloseLocationValue()) && fs.getCloseLocationValue().compareTo(new BigDecimal("0.65")) >= 0) {
            score = score.add(new BigDecimal("0.08"));
        }

        score = score.add(lsrSweepRsiContrib(fs));

        if (strategyHelper.hasValue(atr) && atr.compareTo(ZERO) > 0) {
            BigDecimal sweepRatio = sweepDist.divide(atr, 4, RoundingMode.HALF_UP);
            if (sweepRatio.compareTo(new BigDecimal("0.25")) >= 0
                    && sweepRatio.compareTo(new BigDecimal("1.40")) <= 0) {
                score = score.add(new BigDecimal("0.06"));
            }
        }

        return score.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal lsrSweepRsiContrib(FeatureStore fs) {
        if (!strategyHelper.hasValue(fs.getRsi())) return ZERO;
        BigDecimal rsi = fs.getRsi();
        if (rsi.compareTo(new BigDecimal("35")) >= 0 && rsi.compareTo(new BigDecimal("45")) <= 0) {
            return new BigDecimal("0.12");
        }
        if (rsi.compareTo(new BigDecimal("45")) > 0 && rsi.compareTo(new BigDecimal("48")) <= 0) {
            return new BigDecimal("0.04");
        }
        return ZERO;
    }

    private BigDecimal calculateLongContinuationScore(
            FeatureStore fs,
            RegimeState regime
    ) {
        BigDecimal score = new BigDecimal("0.33");

        if (regime == RegimeState.BULL_TREND) score = score.add(new BigDecimal("0.10"));
        if (regime == RegimeState.NEUTRAL) score = score.subtract(new BigDecimal("0.03"));

        if (strategyHelper.hasValue(fs.getRelativeVolume20()) && fs.getRelativeVolume20().compareTo(new BigDecimal("1.00")) >= 0) {
            score = score.add(new BigDecimal("0.05"));
        }
        if (strategyHelper.hasValue(fs.getBodyToRangeRatio()) && fs.getBodyToRangeRatio().compareTo(new BigDecimal("0.28")) >= 0) {
            score = score.add(new BigDecimal("0.05"));
        }
        if (strategyHelper.hasValue(fs.getCloseLocationValue()) && fs.getCloseLocationValue().compareTo(new BigDecimal("0.65")) >= 0) {
            score = score.add(new BigDecimal("0.08"));
        }

        if (strategyHelper.hasValue(fs.getRsi())) {
            BigDecimal rsi = fs.getRsi();
            if (rsi.compareTo(new BigDecimal("38")) >= 0 && rsi.compareTo(new BigDecimal("48")) <= 0) {
                score = score.add(new BigDecimal("0.10"));
            } else if (rsi.compareTo(new BigDecimal("48")) > 0 && rsi.compareTo(new BigDecimal("50")) <= 0) {
                score = score.add(new BigDecimal("0.03"));
            }
        }

        return score.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortPremiumScore(
            FeatureStore fs,
            BigDecimal sweepDist,
            BigDecimal atr,
            RegimeState regime
    ) {
        BigDecimal score = new BigDecimal("0.35");

        if (regime == RegimeState.BEAR_TREND) score = score.add(new BigDecimal("0.12"));

        if (strategyHelper.hasValue(fs.getRelativeVolume20()) && fs.getRelativeVolume20().compareTo(new BigDecimal("1.25")) >= 0) {
            score = score.add(new BigDecimal("0.08"));
        }
        if (strategyHelper.hasValue(fs.getBodyToRangeRatio()) && fs.getBodyToRangeRatio().compareTo(new BigDecimal("0.48")) >= 0) {
            score = score.add(new BigDecimal("0.08"));
        }
        if (strategyHelper.hasValue(fs.getCloseLocationValue()) && fs.getCloseLocationValue().compareTo(new BigDecimal("0.28")) <= 0) {
            score = score.add(new BigDecimal("0.08"));
        }
        if (strategyHelper.hasValue(fs.getRsi()) && fs.getRsi().compareTo(new BigDecimal("65")) >= 0) {
            score = score.add(new BigDecimal("0.08"));
        }

        if (strategyHelper.hasValue(atr) && atr.compareTo(ZERO) > 0) {
            BigDecimal sweepRatio = sweepDist.divide(atr, 4, RoundingMode.HALF_UP);
            if (sweepRatio.compareTo(new BigDecimal("0.40")) >= 0
                    && sweepRatio.compareTo(new BigDecimal("1.20")) <= 0) {
                score = score.add(new BigDecimal("0.06"));
            }
        }

        return score.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateConfidenceScore(
            EnrichedStrategyContext context,
            BigDecimal signalScore,
            boolean shortSide
    ) {
        BigDecimal confidence = strategyHelper.safe(signalScore);
        confidence = confidence.add(resolveRegimeScore(context).multiply(new BigDecimal("0.08")));

        BigDecimal jumpRisk = resolveJumpRisk(context);
        if (jumpRisk.compareTo(new BigDecimal("0.50")) > 0) {
            confidence = confidence.subtract(new BigDecimal("0.10"));
        }

        if (shortSide) {
            confidence = confidence.subtract(new BigDecimal("0.02"));
        }

        return confidence.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filters
    // ─────────────────────────────────────────────────────────────────────────
    private boolean passesLongSweepFilters(FeatureStore fs, LsrParams p) {
        BigDecimal rsi = strategyHelper.safe(fs.getRsi());
        return rsi.compareTo(p.getLongSweepRsiMin()) >= 0
                && rsi.compareTo(p.getLongSweepRsiMax()) <= 0
                && strategyHelper.hasValue(fs.getBodyToRangeRatio())
                && fs.getBodyToRangeRatio().compareTo(p.getLongSweepBodyMin()) >= 0
                && strategyHelper.hasValue(fs.getCloseLocationValue())
                && fs.getCloseLocationValue().compareTo(p.getLongSweepClvMin()) >= 0
                && strategyHelper.hasValue(fs.getRelativeVolume20())
                && fs.getRelativeVolume20().compareTo(p.getLongSweepRvolMin()) >= 0;
    }

    private boolean passesLongContinuationFilters(FeatureStore fs, LsrParams p) {
        BigDecimal rsi = strategyHelper.safe(fs.getRsi());
        return rsi.compareTo(p.getLongContRsiMin()) >= 0
                && rsi.compareTo(p.getLongContRsiMax()) <= 0
                && strategyHelper.hasValue(fs.getBodyToRangeRatio())
                && fs.getBodyToRangeRatio().compareTo(p.getLongContBodyMin()) >= 0
                && strategyHelper.hasValue(fs.getCloseLocationValue())
                && fs.getCloseLocationValue().compareTo(p.getLongContClvMin()) >= 0
                && strategyHelper.hasValue(fs.getRelativeVolume20())
                && fs.getRelativeVolume20().compareTo(p.getLongContRvolMin()) >= 0;
    }

    private boolean passesShortPremiumFilters(FeatureStore fs, LsrParams p) {
        BigDecimal rsi = strategyHelper.safe(fs.getRsi());
        return rsi.compareTo(p.getShortRsiMin()) >= 0
                && strategyHelper.hasValue(fs.getBodyToRangeRatio())
                && fs.getBodyToRangeRatio().compareTo(p.getShortBodyMin()) >= 0
                && strategyHelper.hasValue(fs.getCloseLocationValue())
                && fs.getCloseLocationValue().compareTo(p.getShortClvMax()) <= 0
                && strategyHelper.hasValue(fs.getRelativeVolume20())
                && fs.getRelativeVolume20().compareTo(p.getShortRvolMin()) >= 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
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

    private boolean isAtrSpikeVetoed(FeatureStore fs, LsrParams p) {
        return strategyHelper.hasValue(fs.getAtrRatio())
                && fs.getAtrRatio().compareTo(p.getAtrRatioExhaustion()) >= 0;
    }

    private boolean isAdxOutsideActiveZone(FeatureStore fs, LsrParams p) {
        if (!strategyHelper.hasValue(fs.getAdx())) return true;
        BigDecimal adx = fs.getAdx();
        return adx.compareTo(p.getAdxEntryMin()) < 0 || adx.compareTo(p.getAdxEntryMax()) > 0;
    }

    private BigDecimal resolveAtr(FeatureStore fs) {
        return (fs != null && fs.getAtr() != null && fs.getAtr().compareTo(ZERO) > 0)
                ? fs.getAtr() : ONE;
    }

    private BigDecimal resolveRegimeScore(EnrichedStrategyContext context) {
        RegimeSnapshot r = context.getRegimeSnapshot();
        return (r != null && r.getTrendScore() != null) ? r.getTrendScore() : ZERO;
    }

    private BigDecimal resolveJumpRisk(EnrichedStrategyContext context) {
        if (context == null) return ZERO;
        VolatilitySnapshot v = context.getVolatilitySnapshot();
        return (v != null && v.getJumpRiskScore() != null) ? v.getJumpRiskScore() : ZERO;
    }

    private BigDecimal resolveRiskMultiplier(EnrichedStrategyContext context) {
        RiskSnapshot r = context.getRiskSnapshot();
        return (r != null && r.getRiskMultiplier() != null) ? r.getRiskMultiplier() : ONE;
    }

    private long computeBarsInTrade(PositionSnapshot snap, MarketData md, String interval) {
        if (snap.getEntryTime() == null || md.getEndTime() == null) return 0;

        long minutes = Duration.between(snap.getEntryTime(), md.getEndTime()).toMinutes();
        if (minutes < 0) return 0;

        return switch (interval) {
            case "5m" -> minutes / 5;
            case "15m" -> minutes / 15;
            case "1h" -> minutes / 60;
            case "4h" -> minutes / 240;
            default -> 0;
        };
    }

    private String inferSetupType(PositionSnapshot snap) {
        if (snap.getPositionRole() == null) {
            return SETUP_LONG_SWEEP;
        }
        String role = snap.getPositionRole().toUpperCase();
        if (role.contains("CONTINUATION")) return SETUP_LONG_CONTINUATION;
        if (role.contains(SIDE_SHORT)) return SETUP_SHORT_EXHAUSTION;
        return SETUP_LONG_SWEEP;
    }

    private Map<String, Object> buildDiagnostics(
            BigDecimal entry,
            String side,
            String setupType
    ) {
        return Map.of(
                "module", "LsrAdaptiveV5StrategyService",
                "side", side,
                "setupType", setupType,
                "entryPrice", entry
        );
    }

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType(SIGNAL_TYPE_SWEEP)
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", TAG_LSR_V5))
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
                .reason("LSR_V5 vetoed by risk layer")
                .jumpRiskScore(resolveJumpRisk(context))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", TAG_LSR_V5, "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }

    private enum RegimeState {
        BULL_TREND,
        BEAR_TREND,
        RANGING,
        NEUTRAL,
        COMPRESSION,
        EXHAUSTION_SPIKE,
        CHAOTIC_TREND
    }
}
