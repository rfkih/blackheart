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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class LsrStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;

    // ─────────────────────────────────────────────────────────────────────────
    // Identity
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

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    // ─────────────────────────────────────────────────────────────────────────
    // Regime / volatility thresholds
    // ─────────────────────────────────────────────────────────────────────────
    private static final BigDecimal ADX_TRENDING_MIN = new BigDecimal("22");
    private static final BigDecimal ADX_COMPRESSION_MAX = new BigDecimal("18");

    private static final BigDecimal ADX_ENTRY_MIN = new BigDecimal("15");
    private static final BigDecimal ADX_ENTRY_MAX = new BigDecimal("30");

    private static final BigDecimal ATR_RATIO_EXHAUSTION = new BigDecimal("2.50");
    private static final BigDecimal ATR_RATIO_CHAOTIC = new BigDecimal("1.80");
    private static final BigDecimal ATR_RATIO_COMPRESS = new BigDecimal("0.70");

    // ─────────────────────────────────────────────────────────────────────────
    // Risk / exits
    // ─────────────────────────────────────────────────────────────────────────
    private static final BigDecimal STOP_ATR_BUFFER = new BigDecimal("0.20");
    private static final BigDecimal MAX_RISK_PCT = new BigDecimal("0.03");

    // ── TP ratios raised to overcome round-trip fee drag (~0.15% of notional).
    // Previous: SWEEP 1.60R, CONT 1.40R, SHORT 1.20R — average winner barely cleared fees.
    // New targets ensure each winning trade generates meaningful net profit above fees.
    private static final BigDecimal TP1_R_LONG_SWEEP = new BigDecimal("2.00");
    private static final BigDecimal TP1_R_LONG_CONTINUATION = new BigDecimal("1.80");
    private static final BigDecimal TP1_R_SHORT = new BigDecimal("1.60");

    private static final BigDecimal BE_TRIGGER_R_LONG_SWEEP = new BigDecimal("0.90");
    private static final BigDecimal BE_TRIGGER_R_LONG_CONTINUATION = new BigDecimal("0.80");
    private static final BigDecimal BE_TRIGGER_R_SHORT = new BigDecimal("0.60");

    // ── Fee buffer applied when moving SL to "break-even".
    // Setting SL to exact entry (0R) always produces a net loss = entry fee + exit fee.
    // Moving SL to entry + 0.20R ensures the exit generates enough gross profit to cover fees.
    private static final BigDecimal BE_FEE_BUFFER_R = new BigDecimal("0.20");

    // ── Time-stop bars extended to give trades more room to reach the raised TP targets.
    private static final int TIME_STOP_BARS_LONG_SWEEP = 14;
    private static final int TIME_STOP_BARS_LONG_CONTINUATION = 12;
    private static final int TIME_STOP_BARS_SHORT = 10;

    // ── Time-stop minimum R raised from 0.08–0.15R to 0.50–0.60R.
    // At tight stops (riskPerUnit ≈ 1% of entry), 0.10R profit = ~$0.09 — less than the ~$0.135 fee.
    // 0.50–0.60R ensures time-stopped exits always generate net profit above the fee cost.
    private static final BigDecimal TIME_STOP_MIN_R_LONG_SWEEP = new BigDecimal("0.60");
    private static final BigDecimal TIME_STOP_MIN_R_LONG_CONTINUATION = new BigDecimal("0.50");
    private static final BigDecimal TIME_STOP_MIN_R_SHORT = new BigDecimal("0.50");

    private static final BigDecimal SHORT_NOTIONAL_MULTIPLIER = new BigDecimal("0.70");
    private static final BigDecimal LONG_CONTINUATION_NOTIONAL_MULTIPLIER = new BigDecimal("0.85");

    // ─────────────────────────────────────────────────────────────────────────
    // Long sweep reclaim setup
    // ─────────────────────────────────────────────────────────────────────────
    private static final BigDecimal LONG_SWEEP_MIN_ATR = new BigDecimal("0.15");
    private static final BigDecimal LONG_SWEEP_MAX_ATR = new BigDecimal("2.20");
    private static final BigDecimal LONG_SWEEP_RSI_MIN = new BigDecimal("35");
    private static final BigDecimal LONG_SWEEP_RSI_MAX = new BigDecimal("48");
    private static final BigDecimal LONG_SWEEP_RVOL_MIN = new BigDecimal("0.95");
    private static final BigDecimal LONG_SWEEP_BODY_MIN = new BigDecimal("0.28");
    private static final BigDecimal LONG_SWEEP_CLV_MIN = new BigDecimal("0.58");
    // ── Raised from 0.48 → 0.72. Backtest showed trades below 0.72 had near-zero gross edge;
    // fees turned them all net-negative. Only high-conviction sweeps (score ≥ 0.72, conf ≥ 0.75) are kept.
    private static final BigDecimal MIN_SIGNAL_SCORE_LONG_SWEEP = new BigDecimal("0.72");
    private static final BigDecimal MIN_CONFIDENCE_SCORE_LONG_SWEEP = new BigDecimal("0.75");

    // ─────────────────────────────────────────────────────────────────────────
    // Long continuation reclaim setup
    // ─────────────────────────────────────────────────────────────────────────
    private static final BigDecimal LONG_CONT_RSI_MIN = new BigDecimal("38");
    private static final BigDecimal LONG_CONT_RSI_MAX = new BigDecimal("50");
    private static final BigDecimal LONG_CONT_RVOL_MIN = new BigDecimal("0.90");
    private static final BigDecimal LONG_CONT_BODY_MIN = new BigDecimal("0.24");
    private static final BigDecimal LONG_CONT_CLV_MIN = new BigDecimal("0.60");
    private static final BigDecimal LONG_CONT_DONCHIAN_BUFFER_ATR = new BigDecimal("0.10");
    // ── Raised from 0.46 → 0.72, same rationale as sweep.
    private static final BigDecimal MIN_SIGNAL_SCORE_LONG_CONT = new BigDecimal("0.72");
    private static final BigDecimal MIN_CONFIDENCE_SCORE_LONG_CONT = new BigDecimal("0.75");

    // ─────────────────────────────────────────────────────────────────────────
    // Premium short exhaustion setup
    // ─────────────────────────────────────────────────────────────────────────
    private static final BigDecimal SHORT_SWEEP_MIN_ATR = new BigDecimal("0.25");
    private static final BigDecimal SHORT_SWEEP_MAX_ATR = new BigDecimal("1.80");
    private static final BigDecimal SHORT_RSI_MIN = new BigDecimal("65");
    private static final BigDecimal SHORT_RVOL_MIN = new BigDecimal("1.20");
    private static final BigDecimal SHORT_BODY_MIN = new BigDecimal("0.42");
    private static final BigDecimal SHORT_CLV_MAX = new BigDecimal("0.30");
    private static final BigDecimal MIN_SIGNAL_SCORE_SHORT = new BigDecimal("0.60");

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

        MarketData md = context.getMarketData();
        FeatureStore fs = context.getFeatureStore();

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid close price");
        }

        if (isMarketVetoed(context)) {
            return veto("Market vetoed by quality / jump-risk filter", context);
        }

        if (isAtrSpikeVetoed(fs)) {
            return veto("ATR spike veto — exhaustion or chaotic volatility regime", context);
        }

        if (isAdxOutsideActiveZone(fs)) {
            return hold(context, "ADX outside active zone [15, 30]");
        }

        PositionSnapshot snap = context.getPositionSnapshot();
        if (context.hasTradablePosition() && snap != null) {
            return managePosition(context, md, fs, snap);
        }

        if (context.getPreviousFeatureStore() == null) {
            return hold(context, "Previous FeatureStore unavailable");
        }

        FeatureStore prevFs = context.getPreviousFeatureStore();
        RegimeState regime = classifyRegime(context, fs);

        if (regime == RegimeState.COMPRESSION
                || regime == RegimeState.EXHAUSTION_SPIKE
                || regime == RegimeState.CHAOTIC_TREND) {
            return hold(context, "Regime veto: " + regime.name());
        }


        if ((regime == RegimeState.BULL_TREND
                || regime == RegimeState.RANGING
                || regime == RegimeState.NEUTRAL)
                && context.isLongAllowed()) {
            StrategyDecision sweepLong = tryLongSweepReclaimEntry(context, md, fs, prevFs, regime);
            if (sweepLong != null) return sweepLong;

            StrategyDecision contLong = tryLongContinuationReclaimEntry(context, md, fs, prevFs, regime);
            if (contLong != null) return contLong;
        }

        if ((regime == RegimeState.BEAR_TREND || regime == RegimeState.RANGING)
                && context.isShortAllowed()) {
            StrategyDecision shortPremium = tryShortExhaustionEntry(context, md, fs, prevFs, regime);
            if (shortPremium != null) return shortPremium;
        }

        return hold(context, "No qualified LSR adaptive v5 setup");
    }


    private RegimeState classifyRegime(EnrichedStrategyContext context, FeatureStore fs) {
        if (strategyHelper.hasValue(fs.getAtrRatio())
                && fs.getAtrRatio().compareTo(ATR_RATIO_EXHAUSTION) >= 0) {
            return RegimeState.EXHAUSTION_SPIKE;
        }

        FeatureStore bias = context.getBiasFeatureStore();
        MarketData biasData = context.getBiasMarketData();

        // No 4H bias data -> fallback to NEUTRAL
        if (bias == null || biasData == null) {
            return RegimeState.NEUTRAL;
        }

        BigDecimal adx4h = strategyHelper.hasValue(bias.getAdx())
                ? bias.getAdx()
                : ZERO;

        // 2) Chaotic trend override
        if (adx4h.compareTo(ADX_TRENDING_MIN) >= 0
                && strategyHelper.hasValue(fs.getAtrRatio())
                && fs.getAtrRatio().compareTo(ATR_RATIO_CHAOTIC) >= 0) {
            return RegimeState.CHAOTIC_TREND;
        }

        // 3) Trending regimes
        if (adx4h.compareTo(ADX_TRENDING_MIN) >= 0
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

        // 4) Compression
        if (adx4h.compareTo(ADX_COMPRESSION_MAX) < 0
                && strategyHelper.hasValue(fs.getAtrRatio())
                && fs.getAtrRatio().compareTo(ATR_RATIO_COMPRESS) < 0) {
            return RegimeState.COMPRESSION;
        }

        // 5) Ranging vs Neutral fallback
        if (adx4h.compareTo(new BigDecimal("20")) <= 0) {
            return RegimeState.RANGING;
        }

        return RegimeState.NEUTRAL;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Setup A: Long sweep reclaim
    // ─────────────────────────────────────────────────────────────────────────
    private StrategyDecision tryLongSweepReclaimEntry(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            FeatureStore prevFs,
            RegimeState regime
    ) {
        if (!strategyHelper.hasValue(prevFs.getDonchianLower20())) return null;
        if (!passesLongSweepFilters(fs)) return null;

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
        if (sweepDist.compareTo(atr.multiply(LONG_SWEEP_MIN_ATR)) < 0
                || sweepDist.compareTo(atr.multiply(LONG_SWEEP_MAX_ATR)) > 0) {
            return null;
        }

        if (regime == RegimeState.RANGING && strategyHelper.hasValue(fs.getDonchianMid20())) {
            if (close.compareTo(fs.getDonchianMid20()) > 0) return null;
        }

        BigDecimal entryPrice = close;
        BigDecimal stopLoss = low.subtract(atr.multiply(STOP_ATR_BUFFER));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);

        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entryPrice.multiply(MAX_RISK_PCT)) > 0) return null;

        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(TP1_R_LONG_SWEEP));

        BigDecimal signalScore = calculateLongSweepScore(context, fs, sweepDist, atr, regime);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore, false);

        if (signalScore.compareTo(MIN_SIGNAL_SCORE_LONG_SWEEP) < 0
                || confidenceScore.compareTo(MIN_CONFIDENCE_SCORE_LONG_SWEEP) < 0) {
            return null;
        }

        FeatureStore biasFs = context.getBiasFeatureStore();
        if (biasFs != null && "NEUTRAL".equalsIgnoreCase(biasFs.getTrendRegime())) {
            return null;
        }

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_LONG);
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
                .tags(List.of("ENTRY", "LSR_V5", "LONG", "SWEEP_RECLAIM", regime.name()))
                .diagnostics(buildDiagnostics(fs, entryPrice, stopLoss, tp1, sweepLevel, sweepDist, signalScore, confidenceScore, SIDE_LONG, SETUP_LONG_SWEEP))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup B: Long continuation reclaim
    // ─────────────────────────────────────────────────────────────────────────
    private StrategyDecision tryLongContinuationReclaimEntry(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            FeatureStore prevFs,
            RegimeState regime
    ) {
        if (!strategyHelper.hasValue(prevFs.getDonchianLower20())) return null;
        if (!passesLongContinuationFilters(fs)) return null;

        BigDecimal donchianLower = prevFs.getDonchianLower20();
        BigDecimal low = strategyHelper.safe(md.getLowPrice());
        BigDecimal open = strategyHelper.safe(md.getOpenPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal atr = resolveAtr(fs);

        BigDecimal lowerBuffer = donchianLower.add(atr.multiply(LONG_CONT_DONCHIAN_BUFFER_ATR));

        boolean nearLowerBoundary = low.compareTo(lowerBuffer) <= 0;
        boolean reclaim = close.compareTo(donchianLower) >= 0;
        boolean bullishCandle = close.compareTo(open) > 0;

        if (!nearLowerBoundary || !reclaim || !bullishCandle) return null;

        // Avoid duplicating true sweep setup too aggressively
        if (low.compareTo(donchianLower) < 0) {
            // If it is an actual sweep, let setup A own it.
            return null;
        }

        if (regime == RegimeState.RANGING && strategyHelper.hasValue(fs.getDonchianMid20())) {
            if (close.compareTo(fs.getDonchianMid20()) > 0) return null;
        }

        BigDecimal entryPrice = close;
        BigDecimal stopLoss = low.subtract(atr.multiply(STOP_ATR_BUFFER));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);

        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entryPrice.multiply(MAX_RISK_PCT)) > 0) return null;

        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(TP1_R_LONG_CONTINUATION));

        BigDecimal signalScore = calculateLongContinuationScore(context, fs, atr, regime);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore, false);

        if (signalScore.compareTo(MIN_SIGNAL_SCORE_LONG_CONT) < 0
                || confidenceScore.compareTo(MIN_CONFIDENCE_SCORE_LONG_CONT) < 0) {
            return null;
        }

        FeatureStore biasFsCont = context.getBiasFeatureStore();
        if (biasFsCont != null && "NEUTRAL".equalsIgnoreCase(biasFsCont.getTrendRegime())) {
            return null;
        }

        BigDecimal baseNotional = strategyHelper.calculateEntryNotional(context, SIDE_LONG);
        BigDecimal notionalSize = baseNotional.multiply(LONG_CONTINUATION_NOTIONAL_MULTIPLIER).setScale(8, RoundingMode.HALF_UP);

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
                .tags(List.of("ENTRY", "LSR_V5", "LONG", "CONTINUATION_RECLAIM", regime.name()))
                .diagnostics(buildDiagnostics(fs, entryPrice, stopLoss, tp1, donchianLower, low, signalScore, confidenceScore, SIDE_LONG, SETUP_LONG_CONTINUATION))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup C: Premium short exhaustion
    // ─────────────────────────────────────────────────────────────────────────
    private StrategyDecision tryShortExhaustionEntry(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            FeatureStore prevFs,
            RegimeState regime
    ) {
        if (!strategyHelper.hasValue(prevFs.getDonchianUpper20())) return null;
        if (!passesShortPremiumFilters(fs)) return null;

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
        if (sweepDist.compareTo(atr.multiply(SHORT_SWEEP_MIN_ATR)) < 0
                || sweepDist.compareTo(atr.multiply(SHORT_SWEEP_MAX_ATR)) > 0) {
            return null;
        }

        if (regime == RegimeState.RANGING && strategyHelper.hasValue(fs.getDonchianMid20())) {
            if (close.compareTo(fs.getDonchianMid20()) < 0) return null;
        }

        BigDecimal entryPrice = close;
        BigDecimal stopLoss = high.add(atr.multiply(STOP_ATR_BUFFER));
        BigDecimal riskPerUnit = stopLoss.subtract(entryPrice);

        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entryPrice.multiply(MAX_RISK_PCT)) > 0) return null;

        BigDecimal tp1 = entryPrice.subtract(riskPerUnit.multiply(TP1_R_SHORT));

        BigDecimal signalScore = calculateShortPremiumScore(context, fs, sweepDist, atr, regime);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore, true);

        if (signalScore.compareTo(MIN_SIGNAL_SCORE_SHORT) < 0
                || confidenceScore.compareTo(MIN_SIGNAL_SCORE_SHORT) < 0) {
            return null;
        }

        BigDecimal baseNotional = strategyHelper.calculateEntryNotional(context, SIDE_SHORT);
        BigDecimal notionalSize = baseNotional.multiply(SHORT_NOTIONAL_MULTIPLIER).setScale(8, RoundingMode.HALF_UP);

        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Short premium notional size is zero");
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
                .tags(List.of("ENTRY", "LSR_V5", "SHORT", "PREMIUM_EXHAUSTION", regime.name()))
                .diagnostics(buildDiagnostics(fs, entryPrice, stopLoss, tp1, sweepLevel, sweepDist, signalScore, confidenceScore, SIDE_SHORT, SETUP_SHORT_EXHAUSTION))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Position management
    // ─────────────────────────────────────────────────────────────────────────
    private StrategyDecision managePosition(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            PositionSnapshot snap
    ) {
        if (snap.getSide() == null || snap.getEntryPrice() == null || snap.getCurrentStopLossPrice() == null) {
            return hold(context, "Position management incomplete");
        }

        String setupType = inferSetupType(snap);

        if (SIDE_LONG.equalsIgnoreCase(snap.getSide())) {
            return manageLongPosition(context, md, fs, snap, setupType);
        }

        if (SIDE_SHORT.equalsIgnoreCase(snap.getSide())) {
            return manageShortPosition(context, md, snap);
        }

        return hold(context, "Unknown position side");
    }

    private StrategyDecision manageLongPosition(
            EnrichedStrategyContext context,
            MarketData md,
            FeatureStore fs,
            PositionSnapshot snap,
            String setupType
    ) {
        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal risk = entry.subtract(curStop);

        if (risk.compareTo(ZERO) <= 0) return hold(context, "Invalid long risk");

        boolean continuation = SETUP_LONG_CONTINUATION.equals(setupType);

        int timeStopBars = continuation ? TIME_STOP_BARS_LONG_CONTINUATION : TIME_STOP_BARS_LONG_SWEEP;
        BigDecimal timeStopMinR = continuation ? TIME_STOP_MIN_R_LONG_CONTINUATION : TIME_STOP_MIN_R_LONG_SWEEP;
        BigDecimal beTriggerR = continuation ? BE_TRIGGER_R_LONG_CONTINUATION : BE_TRIGGER_R_LONG_SWEEP;

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
                        .tags(List.of("MANAGEMENT", "LSR_V5", "LONG", "TIME_STOP"))
                        .build();
            }
        }

        BigDecimal threshold = risk.multiply(beTriggerR);
        if (close.subtract(entry).compareTo(threshold) < 0) {
            return hold(context, "Long BE not triggered");
        }
        BigDecimal longBeStop = entry.add(risk.multiply(BE_FEE_BUFFER_R));
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
                .tags(List.of("MANAGEMENT", "LSR_V5", "LONG", "BREAK_EVEN"))
                .build();
    }

    private StrategyDecision manageShortPosition(
            EnrichedStrategyContext context,
            MarketData md,
            PositionSnapshot snap
    ) {
        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal risk = curStop.subtract(entry);

        if (risk.compareTo(ZERO) <= 0) return hold(context, "Invalid short risk");

        long bars = computeBarsInTrade(snap, md, context.getInterval());
        if (bars >= TIME_STOP_BARS_SHORT) {
            BigDecimal profitR = entry.subtract(close).divide(risk, 6, RoundingMode.HALF_UP);
            if (profitR.compareTo(TIME_STOP_MIN_R_SHORT) < 0) {
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
                        .tags(List.of("MANAGEMENT", "LSR_V5", "SHORT", "TIME_STOP"))
                        .build();
            }
        }

        BigDecimal threshold = risk.multiply(BE_TRIGGER_R_SHORT);
        if (entry.subtract(close).compareTo(threshold) < 0) {
            return hold(context, "Short BE not triggered");
        }
        BigDecimal shortBeStop = entry.subtract(risk.multiply(BE_FEE_BUFFER_R));
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
                .tags(List.of("MANAGEMENT", "LSR_V5", "SHORT", "BREAK_EVEN"))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoring
    // ─────────────────────────────────────────────────────────────────────────
    private BigDecimal calculateLongSweepScore(
            EnrichedStrategyContext context,
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

        if (strategyHelper.hasValue(fs.getRsi())) {
            BigDecimal rsi = fs.getRsi();
            if (rsi.compareTo(new BigDecimal("35")) >= 0 && rsi.compareTo(new BigDecimal("45")) <= 0) {
                score = score.add(new BigDecimal("0.12"));
            } else if (rsi.compareTo(new BigDecimal("45")) > 0 && rsi.compareTo(new BigDecimal("48")) <= 0) {
                score = score.add(new BigDecimal("0.04"));
            }
        }

        if (strategyHelper.hasValue(atr) && atr.compareTo(ZERO) > 0) {
            BigDecimal sweepRatio = sweepDist.divide(atr, 4, RoundingMode.HALF_UP);
            if (sweepRatio.compareTo(new BigDecimal("0.25")) >= 0
                    && sweepRatio.compareTo(new BigDecimal("1.40")) <= 0) {
                score = score.add(new BigDecimal("0.06"));
            }
        }

        return score.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateLongContinuationScore(
            EnrichedStrategyContext context,
            FeatureStore fs,
            BigDecimal atr,
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
            EnrichedStrategyContext context,
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
    private boolean passesLongSweepFilters(FeatureStore fs) {
        BigDecimal rsi = strategyHelper.safe(fs.getRsi());
        if (rsi.compareTo(LONG_SWEEP_RSI_MIN) < 0 || rsi.compareTo(LONG_SWEEP_RSI_MAX) > 0) return false;

        if (!strategyHelper.hasValue(fs.getBodyToRangeRatio())
                || fs.getBodyToRangeRatio().compareTo(LONG_SWEEP_BODY_MIN) < 0) return false;
        if (!strategyHelper.hasValue(fs.getCloseLocationValue())
                || fs.getCloseLocationValue().compareTo(LONG_SWEEP_CLV_MIN) < 0) return false;
        if (!strategyHelper.hasValue(fs.getRelativeVolume20())
                || fs.getRelativeVolume20().compareTo(LONG_SWEEP_RVOL_MIN) < 0) return false;

        return true;
    }

    private boolean passesLongContinuationFilters(FeatureStore fs) {
        BigDecimal rsi = strategyHelper.safe(fs.getRsi());
        if (rsi.compareTo(LONG_CONT_RSI_MIN) < 0 || rsi.compareTo(LONG_CONT_RSI_MAX) > 0) return false;

        if (!strategyHelper.hasValue(fs.getBodyToRangeRatio())
                || fs.getBodyToRangeRatio().compareTo(LONG_CONT_BODY_MIN) < 0) return false;
        if (!strategyHelper.hasValue(fs.getCloseLocationValue())
                || fs.getCloseLocationValue().compareTo(LONG_CONT_CLV_MIN) < 0) return false;
        if (!strategyHelper.hasValue(fs.getRelativeVolume20())
                || fs.getRelativeVolume20().compareTo(LONG_CONT_RVOL_MIN) < 0) return false;

        return true;
    }

    private boolean passesShortPremiumFilters(FeatureStore fs) {
        BigDecimal rsi = strategyHelper.safe(fs.getRsi());
        if (rsi.compareTo(SHORT_RSI_MIN) < 0) return false;

        if (!strategyHelper.hasValue(fs.getBodyToRangeRatio())
                || fs.getBodyToRangeRatio().compareTo(SHORT_BODY_MIN) < 0) return false;
        if (!strategyHelper.hasValue(fs.getCloseLocationValue())
                || fs.getCloseLocationValue().compareTo(SHORT_CLV_MAX) > 0) return false;
        if (!strategyHelper.hasValue(fs.getRelativeVolume20())
                || fs.getRelativeVolume20().compareTo(SHORT_RVOL_MIN) < 0) return false;

        return true;
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

    private boolean isAtrSpikeVetoed(FeatureStore fs) {
        return strategyHelper.hasValue(fs.getAtrRatio())
                && fs.getAtrRatio().compareTo(ATR_RATIO_EXHAUSTION) >= 0;
    }

    private boolean isAdxOutsideActiveZone(FeatureStore fs) {
        if (!strategyHelper.hasValue(fs.getAdx())) return true;
        BigDecimal adx = fs.getAdx();
        return adx.compareTo(ADX_ENTRY_MIN) < 0 || adx.compareTo(ADX_ENTRY_MAX) > 0;
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
        if (role.contains("SHORT")) return SETUP_SHORT_EXHAUSTION;
        return SETUP_LONG_SWEEP;
    }

    private Map<String, Object> buildDiagnostics(
            FeatureStore fs,
            BigDecimal entry,
            BigDecimal stop,
            BigDecimal tp1,
            BigDecimal sweepLevel,
            BigDecimal sweepDist,
            BigDecimal signal,
            BigDecimal confidence,
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
                .tags(List.of("HOLD", "LSR_V5"))
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
                .tags(List.of("VETO", "LSR_V5", "RISK_LAYER"))
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