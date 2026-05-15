package id.co.blackheart.engine;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.service.strategy.StrategyHelper;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Trend-pullback archetype — generalised TPR.
 *
 * <p>Pullback to EMA20 inside a confirmed trend, reclaimed by a body+CLV+volume
 * candle. Two-leg exit: TP1 leg moves to break-even at {@code breakEvenR};
 * runner phase-trails by ATR after {@code runnerPhase2R}/{@code runnerPhase3R}
 * with a minimum locked R-multiple per phase.
 *
 * <p>Defaults mirror {@code TrendPullbackStrategyService.Params.defaults()} so a
 * spec with empty params produces TPR-shaped behaviour. Per-account tuning
 * lives in {@link StrategySpec#getParams()} (override layer in
 * {@code strategy_param.param_overrides}).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TrendPullbackEngine implements StrategyEngine {

    public static final String ARCHETYPE_NAME = "trend_pullback";
    public static final int VERSION = 1;

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String KEY_ENTRY = "entry";

    private static final String DEFAULT_SIGNAL_RECLAIM     = "TPR_TREND_PULLBACK_RECLAIM";
    private static final String SIGNAL_TYPE_MANAGEMENT     = "POSITION_MANAGEMENT";
    private static final String DEFAULT_SETUP_LONG_RECLAIM  = "TPR_LONG_RECLAIM";
    private static final String DEFAULT_SETUP_SHORT_RECLAIM = "TPR_SHORT_RECLAIM";
    private static final String DEFAULT_SETUP_LONG_BE       = "TPR_LONG_BREAK_EVEN";
    private static final String DEFAULT_SETUP_SHORT_BE      = "TPR_SHORT_BREAK_EVEN";
    private static final String DEFAULT_SETUP_LONG_TRAIL    = "TPR_LONG_TRAIL";
    private static final String DEFAULT_SETUP_SHORT_TRAIL   = "TPR_SHORT_TRAIL";

    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String TARGET_ALL = "ALL";
    private static final String POSITION_ROLE_TP1    = "TP1";
    private static final String POSITION_ROLE_RUNNER = "RUNNER";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // Defaults — kept in sync with TPR Params.defaults() so an empty spec
    // params object reproduces TPR exactly. Tuning happens through spec
    // params, never through edits to these constants.
    private static final BigDecimal D_EMA50_SLOPE_MIN     = new BigDecimal("0");
    private static final BigDecimal D_BIAS_ADX_MIN        = new BigDecimal("25");
    private static final BigDecimal D_BIAS_ADX_MAX        = new BigDecimal("40");
    private static final BigDecimal D_ADX_ENTRY_MIN       = new BigDecimal("25");
    private static final BigDecimal D_ADX_ENTRY_MAX       = new BigDecimal("45");
    private static final BigDecimal D_DI_SPREAD_MIN       = new BigDecimal("2.0");
    private static final BigDecimal D_PULLBACK_TOUCH_ATR  = new BigDecimal("0.40");
    private static final BigDecimal D_LONG_RSI_MIN        = new BigDecimal("38");
    private static final BigDecimal D_LONG_RSI_MAX        = new BigDecimal("60");
    private static final BigDecimal D_SHORT_RSI_MIN       = new BigDecimal("40");
    private static final BigDecimal D_SHORT_RSI_MAX       = new BigDecimal("62");
    private static final BigDecimal D_BODY_RATIO_MIN      = new BigDecimal("0.45");
    private static final BigDecimal D_CLV_MIN             = new BigDecimal("0.70");
    private static final BigDecimal D_CLV_MAX             = new BigDecimal("0.90");
    private static final BigDecimal D_RVOL_MIN            = new BigDecimal("1.10");
    private static final BigDecimal D_STOP_ATR_BUFFER     = new BigDecimal("0.55");
    private static final BigDecimal D_MAX_ENTRY_RISK_PCT  = new BigDecimal("0.04");
    private static final BigDecimal D_TP1_R               = new BigDecimal("2.00");
    private static final BigDecimal D_BREAK_EVEN_R        = new BigDecimal("1.00");
    private static final BigDecimal D_RUNNER_BE_R         = new BigDecimal("1.00");
    private static final BigDecimal D_RUNNER_PHASE2_R     = new BigDecimal("2.00");
    private static final BigDecimal D_RUNNER_PHASE3_R     = new BigDecimal("3.50");
    private static final BigDecimal D_RUNNER_ATR_PHASE2   = new BigDecimal("2.50");
    private static final BigDecimal D_RUNNER_ATR_PHASE3   = new BigDecimal("1.80");
    private static final BigDecimal D_RUNNER_LOCK_PHASE2  = new BigDecimal("1.00");
    private static final BigDecimal D_RUNNER_LOCK_PHASE3  = new BigDecimal("2.50");
    private static final BigDecimal D_MIN_SIGNAL_SCORE    = new BigDecimal("0.55");
    // V56 — riskPerTradePct/maxAllocationPct moved to account_strategy.

    private final StrategyHelper strategyHelper;

    @Override public String archetype() { return ARCHETYPE_NAME; }
    @Override public int supportedVersion() { return VERSION; }

    @Override
    public StrategyRequirements requirements(StrategySpec spec) {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval(spec.bodyString("bias.interval", "4h"))
                .requireRegimeSnapshot(true)
                .requireVolatilitySnapshot(true)
                .requireRiskSnapshot(true)
                .requireMarketQualitySnapshot(true)
                .requirePreviousFeatureStore(true)
                .build();
    }

    @Override
    public StrategyDecision evaluate(StrategySpec spec, EnrichedStrategyContext context) {
        if (ObjectUtils.isEmpty(context) || ObjectUtils.isEmpty(context.getMarketData()) || ObjectUtils.isEmpty(context.getFeatureStore())) {
            return hold(spec, context, "Invalid context");
        }
        Tuning t = Tuning.from(spec);
        MarketData md = context.getMarketData();
        FeatureStore f = context.getFeatureStore();
        PositionSnapshot snap = context.getPositionSnapshot();

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ZERO) <= 0) return hold(spec, context, "Invalid close price");

        if (EngineContextHelpers.isMarketVetoed(context)) return veto(spec, context, "Market vetoed");

        if (context.hasTradablePosition() && ObjectUtils.isNotEmpty(snap)) {
            return managePosition(spec, context, md, f, snap, t);
        }

        if (context.isLongAllowed()) {
            StrategyDecision d = tryLongEntry(spec, context, md, f, t);
            if (ObjectUtils.isNotEmpty(d)) return d;
        }
        if (context.isShortAllowed()) {
            StrategyDecision d = tryShortEntry(spec, context, md, f, t);
            if (ObjectUtils.isNotEmpty(d)) return d;
        }
        return hold(spec, context, "No qualified TPR setup");
    }

    // ── Entries ──────────────────────────────────────────────────────────────

    private StrategyDecision tryLongEntry(StrategySpec spec, EnrichedStrategyContext ctx,
                                          MarketData md, FeatureStore f, Tuning t) {
        if (!longEnvironmentOk(ctx, md, f, t)) return null;

        BigDecimal atr = EngineContextHelpers.resolveAtr(f);
        BigDecimal ema20 = f.getEma20();
        if (ObjectUtils.isEmpty(atr) || ObjectUtils.isEmpty(ema20)) return null;

        BigDecimal low = strategyHelper.safe(md.getLowPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (!longCandleQualityOk(f, t, low, close, ema20, atr)) return null;

        BigDecimal entry = close;
        BigDecimal stop = low.subtract(atr.multiply(t.stopAtrBuffer));
        BigDecimal riskPerUnit = entry.subtract(stop);
        if (!riskBracketOk(entry, riskPerUnit, t)) return null;

        BigDecimal score = longSignalScore(f, t);
        if (score.compareTo(t.minSignalScore) < 0) return null;

        BigDecimal notional = pickLongNotional(ctx, entry, stop);
        if (notional.compareTo(ZERO) <= 0) return hold(spec, ctx, "TPR long notional zero");

        BigDecimal tp1 = entry.add(riskPerUnit.multiply(t.tp1R));
        // stopDist% = structural stop distance. Per-trade risk pct lives on
        // account_strategy now (V56) and is logged by StrategyHelper at the
        // sizing call site, so we don't shadow it here.
        log.info("TPR[{}] LONG ENTRY | time={} entry={} stop={} tp1={} stopDist%={} notional={} score={}",
                spec.getStrategyCode(), md.getEndTime(), entry, stop, tp1,
                riskPerUnit.divide(entry, 4, RoundingMode.HALF_UP).multiply(HUNDRED),
                notional, score);

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.OPEN_LONG)
                .signalType(signalReclaim(spec)).setupType(setupLongReclaim(spec)).side(SIDE_LONG)
                .reason("TPR long: pullback to EMA20 reclaimed in confirmed uptrend")
                .signalScore(score).confidenceScore(score)
                .regimeScore(EngineContextHelpers.resolveRegimeScore(ctx)).riskMultiplier(EngineContextHelpers.resolveRiskMultiplier(ctx))
                .jumpRiskScore(EngineContextHelpers.resolveJumpRisk(ctx))
                .notionalSize(notional)
                .stopLossPrice(stop).takeProfitPrice1(tp1)
                .exitStructure(EXIT_STRUCTURE_TP1_RUNNER).targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx()).entryAtr(f.getAtr()).entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", spec.getStrategyCode(), SIDE_LONG, ARCHETYPE_NAME))
                .diagnostics(Map.of(KEY_ENTRY, entry, "stop", stop, "tp1", tp1,
                        "ema20", ema20, "atr", atr, "rsi", f.getRsi()))
                .build();
    }

    private boolean longEnvironmentOk(EnrichedStrategyContext ctx, MarketData md, FeatureStore f, Tuning t) {
        return isBullishBias(ctx, t)
                && hasBullishEmaStack(md, f, t)
                && isAdxBandOk(f, t)
                && isLongDiSpreadOk(f, t);
    }

    private boolean longCandleQualityOk(FeatureStore f, Tuning t,
                                        BigDecimal low, BigDecimal close,
                                        BigDecimal ema20, BigDecimal atr) {
        BigDecimal pullbackTol = atr.multiply(t.pullbackTouchAtr);
        if (low.subtract(ema20).compareTo(pullbackTol) > 0) return false;
        if (ObjectUtils.isEmpty(f.getRsi())
                || f.getRsi().compareTo(t.longRsiMin) < 0
                || f.getRsi().compareTo(t.longRsiMax) > 0) return false;
        if (close.compareTo(ema20) <= 0) return false;
        if (ObjectUtils.isEmpty(f.getBodyToRangeRatio())
                || f.getBodyToRangeRatio().compareTo(t.bodyRatioMin) < 0) return false;
        if (ObjectUtils.isEmpty(f.getCloseLocationValue())
                || f.getCloseLocationValue().compareTo(t.clvMin) < 0
                || f.getCloseLocationValue().compareTo(t.clvMax) > 0) return false;
        return ObjectUtils.isNotEmpty(f.getRelativeVolume20())
                && f.getRelativeVolume20().compareTo(t.rvolMin) >= 0;
    }

    private boolean riskBracketOk(BigDecimal entry, BigDecimal riskPerUnit, Tuning t) {
        if (riskPerUnit.compareTo(ZERO) <= 0) return false;
        return riskPerUnit.compareTo(entry.multiply(t.maxEntryRiskPct)) <= 0;
    }

    /**
     * V56 — sizing config sourced from {@code account_strategy} via the
     * unified helper. Pre-V56 TPR read {@code riskPerTradePct} and
     * {@code maxAllocationPct} from spec body params with hardcoded defaults
     * (0.02 / 1.00); those fields are now inert and the engine respects the
     * operator's account-level toggle + cap.
     */
    private BigDecimal pickLongNotional(EnrichedStrategyContext ctx, BigDecimal entry,
                                        BigDecimal stop) {
        return strategyHelper.calculateLongEntryNotional(ctx, entry, stop);
    }

    private StrategyDecision tryShortEntry(StrategySpec spec, EnrichedStrategyContext ctx,
                                           MarketData md, FeatureStore f, Tuning t) {
        if (!shortEnvironmentOk(ctx, md, f, t)) return null;

        BigDecimal atr = EngineContextHelpers.resolveAtr(f);
        BigDecimal ema20 = f.getEma20();
        if (ObjectUtils.isEmpty(atr) || ObjectUtils.isEmpty(ema20)) return null;

        BigDecimal high = strategyHelper.safe(md.getHighPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (!shortCandleQualityOk(f, t, high, close, ema20, atr)) return null;

        BigDecimal entry = close;
        BigDecimal stop = high.add(atr.multiply(t.stopAtrBuffer));
        BigDecimal riskPerUnit = stop.subtract(entry);
        if (!riskBracketOk(entry, riskPerUnit, t)) return null;

        BigDecimal score = shortSignalScore(f, t);
        if (score.compareTo(t.minSignalScore) < 0) return null;

        BigDecimal positionSize = pickShortPositionSize(ctx, entry, stop);
        if (positionSize.compareTo(ZERO) <= 0) return hold(spec, ctx, "TPR short position size zero");

        BigDecimal tp1 = entry.subtract(riskPerUnit.multiply(t.tp1R));
        log.info("TPR[{}] SHORT ENTRY | time={} entry={} stop={} tp1={} stopDist%={} positionSize={} score={}",
                spec.getStrategyCode(), md.getEndTime(), entry, stop, tp1,
                riskPerUnit.divide(entry, 4, RoundingMode.HALF_UP).multiply(HUNDRED),
                positionSize, score);

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.OPEN_SHORT)
                .signalType(signalReclaim(spec)).setupType(setupShortReclaim(spec)).side(SIDE_SHORT)
                .reason("TPR short: rally to EMA20 rejected in confirmed downtrend")
                .signalScore(score).confidenceScore(score)
                .regimeScore(EngineContextHelpers.resolveRegimeScore(ctx)).riskMultiplier(EngineContextHelpers.resolveRiskMultiplier(ctx))
                .jumpRiskScore(EngineContextHelpers.resolveJumpRisk(ctx))
                .positionSize(positionSize)
                .stopLossPrice(stop).takeProfitPrice1(tp1)
                .exitStructure(EXIT_STRUCTURE_TP1_RUNNER).targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx()).entryAtr(f.getAtr()).entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", spec.getStrategyCode(), SIDE_SHORT, ARCHETYPE_NAME))
                .diagnostics(Map.of(KEY_ENTRY, entry, "stop", stop, "tp1", tp1,
                        "ema20", ema20, "atr", atr, "rsi", f.getRsi()))
                .build();
    }

    private boolean shortEnvironmentOk(EnrichedStrategyContext ctx, MarketData md, FeatureStore f, Tuning t) {
        return isBearishBias(ctx, t)
                && hasBearishEmaStack(md, f, t)
                && isAdxBandOk(f, t)
                && isShortDiSpreadOk(f, t);
    }

    private boolean shortCandleQualityOk(FeatureStore f, Tuning t,
                                         BigDecimal high, BigDecimal close,
                                         BigDecimal ema20, BigDecimal atr) {
        BigDecimal pullbackTol = atr.multiply(t.pullbackTouchAtr);
        if (ema20.subtract(high).compareTo(pullbackTol) > 0) return false;
        if (ObjectUtils.isEmpty(f.getRsi())
                || f.getRsi().compareTo(t.shortRsiMin) < 0
                || f.getRsi().compareTo(t.shortRsiMax) > 0) return false;
        if (close.compareTo(ema20) >= 0) return false;
        if (ObjectUtils.isEmpty(f.getBodyToRangeRatio())
                || f.getBodyToRangeRatio().compareTo(t.bodyRatioMin) < 0) return false;

        BigDecimal clv = f.getCloseLocationValue();
        BigDecimal clvShortMax = ONE.subtract(t.clvMin);
        BigDecimal clvShortMin = ONE.subtract(t.clvMax);
        if (ObjectUtils.isEmpty(clv) || clv.compareTo(clvShortMax) > 0 || clv.compareTo(clvShortMin) < 0) return false;

        return ObjectUtils.isNotEmpty(f.getRelativeVolume20())
                && f.getRelativeVolume20().compareTo(t.rvolMin) >= 0;
    }

    /**
     * V56 — sizing config sourced from {@code account_strategy} via the
     * unified helper.
     */
    private BigDecimal pickShortPositionSize(EnrichedStrategyContext ctx, BigDecimal entry,
                                             BigDecimal stop) {
        return strategyHelper.calculateShortEntryQty(ctx, entry, stop);
    }

    // ── Position management ──────────────────────────────────────────────────

    private StrategyDecision managePosition(StrategySpec spec, EnrichedStrategyContext ctx,
                                            MarketData md, FeatureStore f,
                                            PositionSnapshot snap, Tuning t) {
        String side = snap.getSide();
        if (ObjectUtils.isEmpty(side)) return hold(spec, ctx, "TPR manage: unknown side");
        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);

        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal initStop = ObjectUtils.isNotEmpty(snap.getInitialStopLossPrice())
                ? snap.getInitialStopLossPrice() : curStop;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal initRisk = isLong ? entry.subtract(initStop) : initStop.subtract(entry);
        if (initRisk.compareTo(ZERO) <= 0) return hold(spec, ctx, "TPR manage: invalid init risk");

        BigDecimal move = isLong ? close.subtract(entry) : entry.subtract(close);
        if (move.compareTo(ZERO) <= 0) return hold(spec, ctx, "TPR manage: not in profit");
        BigDecimal rMultiple = move.divide(initRisk, 8, RoundingMode.HALF_UP);

        MgmtState state = new MgmtState(snap, side, entry, curStop, initRisk, close, rMultiple);
        if (POSITION_ROLE_RUNNER.equalsIgnoreCase(snap.getPositionRole())) {
            return tryRunnerTrail(spec, ctx, f, state, t);
        }
        return tryBreakEvenShift(spec, ctx, state, move, t);
    }

    private StrategyDecision tryBreakEvenShift(StrategySpec spec, EnrichedStrategyContext ctx,
                                               MgmtState s, BigDecimal move, Tuning t) {
        if (move.compareTo(s.initRisk().multiply(t.breakEvenR)) < 0) {
            return hold(spec, ctx, "TPR BE not ready");
        }
        boolean alreadyAtBe = s.isLong()
                ? s.curStop().compareTo(s.entry()) >= 0
                : s.curStop().compareTo(s.entry()) <= 0;
        if (alreadyAtBe) return hold(spec, ctx, "TPR stop already at BE");

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(s.isLong() ? setupLongBe(spec) : setupShortBe(spec)).side(s.side())
                .stopLossPrice(s.entry()).takeProfitPrice1(s.snap().getTakeProfitPrice())
                .targetPositionRole(POSITION_ROLE_TP1)
                .reason("Move TP1 leg to break-even at " + t.breakEvenR + "R")
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", spec.getStrategyCode(), s.side(), "BREAK_EVEN"))
                .diagnostics(Map.of(KEY_ENTRY, s.entry(), "curStop", s.curStop(),
                        "close", s.close(), "rMultiple", s.rMultiple()))
                .build();
    }

    private StrategyDecision tryRunnerTrail(StrategySpec spec, EnrichedStrategyContext ctx,
                                            FeatureStore f, MgmtState s, Tuning t) {
        BigDecimal atr = EngineContextHelpers.resolveAtr(f);
        if (atr == null) return hold(spec, ctx, "TPR runner: no ATR");

        BigDecimal candidate = computeRunnerCandidate(s, atr, t);
        if (ObjectUtils.isEmpty(candidate)) return hold(spec, ctx, "TPR runner not ready");

        boolean improved = s.isLong()
                ? candidate.compareTo(s.curStop()) > 0
                : candidate.compareTo(s.curStop()) < 0;
        if (!improved) return hold(spec, ctx, "TPR runner stop already optimal");

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(s.isLong() ? setupLongTrail(spec) : setupShortTrail(spec)).side(s.side())
                .stopLossPrice(candidate).trailingStopPrice(candidate)
                .takeProfitPrice1(s.snap().getTakeProfitPrice())
                .targetPositionRole(POSITION_ROLE_RUNNER)
                .reason("TPR runner trail at " + s.rMultiple() + "R")
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", spec.getStrategyCode(), s.side(), "TRAIL"))
                .diagnostics(Map.of("rMultiple", s.rMultiple(), "candidate", candidate,
                        "curStop", s.curStop(), "close", s.close(), "atr", atr))
                .build();
    }

    private static BigDecimal computeRunnerCandidate(MgmtState s, BigDecimal atr, Tuning t) {
        if (s.rMultiple().compareTo(t.runnerPhase3R) >= 0) {
            return phaseStop(s.isLong(), s.entry(), s.close(), atr, s.initRisk(),
                    t.runnerAtrPhase3, t.runnerLockPhase3R);
        }
        if (s.rMultiple().compareTo(t.runnerPhase2R) >= 0) {
            return phaseStop(s.isLong(), s.entry(), s.close(), atr, s.initRisk(),
                    t.runnerAtrPhase2, t.runnerLockPhase2R);
        }
        if (s.rMultiple().compareTo(t.runnerBreakEvenR) >= 0) {
            boolean alreadyAtBe = s.isLong()
                    ? s.curStop().compareTo(s.entry()) >= 0
                    : s.curStop().compareTo(s.entry()) <= 0;
            return alreadyAtBe ? null : s.entry();
        }
        return null;
    }

    /** Cohesive view of an open position used by the manage-position helpers. */
    private record MgmtState(
            PositionSnapshot snap,
            String side,
            BigDecimal entry,
            BigDecimal curStop,
            BigDecimal initRisk,
            BigDecimal close,
            BigDecimal rMultiple
    ) {
        boolean isLong() { return SIDE_LONG.equalsIgnoreCase(side); }
    }

    private static BigDecimal phaseStop(boolean isLong, BigDecimal entry, BigDecimal close, BigDecimal atr,
                                        BigDecimal initRisk, BigDecimal atrMult, BigDecimal lockR) {
        BigDecimal trail = isLong
                ? close.subtract(atr.multiply(atrMult))
                : close.add(atr.multiply(atrMult));
        BigDecimal lock = isLong
                ? entry.add(initRisk.multiply(lockR))
                : entry.subtract(initRisk.multiply(lockR));
        return isLong ? trail.max(lock) : trail.min(lock);
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    private BigDecimal longSignalScore(FeatureStore f, Tuning t) {
        BigDecimal rsi = ObjectUtils.isNotEmpty(f.getRsi()) ? normalise(f.getRsi(), t.longRsiMin, t.longRsiMax) : ZERO;
        BigDecimal clv = ObjectUtils.isNotEmpty(f.getCloseLocationValue()) ? f.getCloseLocationValue() : ZERO;
        BigDecimal body = ObjectUtils.isNotEmpty(f.getBodyToRangeRatio()) ? f.getBodyToRangeRatio() : ZERO;
        BigDecimal vol = f.getRelativeVolume20() != null
                ? f.getRelativeVolume20().divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP).min(ONE) : ZERO;
        return weightedScore(rsi, clv, body, vol);
    }

    private BigDecimal shortSignalScore(FeatureStore f, Tuning t) {
        BigDecimal rsi = f.getRsi() != null
                ? normalise(t.shortRsiMax.subtract(f.getRsi()), ZERO, t.shortRsiMax.subtract(t.shortRsiMin))
                : ZERO;
        BigDecimal clv = f.getCloseLocationValue() != null
                ? ONE.subtract(f.getCloseLocationValue()) : ZERO;
        BigDecimal body = ObjectUtils.isNotEmpty(f.getBodyToRangeRatio()) ? f.getBodyToRangeRatio() : ZERO;
        BigDecimal vol = f.getRelativeVolume20() != null
                ? f.getRelativeVolume20().divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP).min(ONE) : ZERO;
        return weightedScore(rsi, clv, body, vol);
    }

    private static BigDecimal weightedScore(BigDecimal rsi, BigDecimal clv, BigDecimal body, BigDecimal vol) {
        return rsi.multiply(new BigDecimal("0.25"))
                .add(clv.multiply(new BigDecimal("0.30")))
                .add(body.multiply(new BigDecimal("0.20")))
                .add(vol.multiply(new BigDecimal("0.25")))
                .min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal normalise(BigDecimal value, BigDecimal lo, BigDecimal hi) {
        if (ObjectUtils.isEmpty(value) || ObjectUtils.isEmpty(lo) || ObjectUtils.isEmpty(hi)) return ZERO;
        BigDecimal range = hi.subtract(lo);
        if (range.compareTo(ZERO) <= 0) return ZERO;
        BigDecimal scaled = value.subtract(lo).divide(range, 8, RoundingMode.HALF_UP);
        if (scaled.compareTo(ZERO) < 0) return ZERO;
        if (scaled.compareTo(ONE) > 0) return ONE;
        return scaled;
    }

    // ── Gating helpers ───────────────────────────────────────────────────────

    private boolean hasBullishEmaStack(MarketData md, FeatureStore f, Tuning t) {
        if (ObjectUtils.isEmpty(f.getEma50()) || ObjectUtils.isEmpty(f.getEma200())) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) <= 0) return false;
        if (f.getEma50().compareTo(f.getEma200()) <= 0) return false;
        return ObjectUtils.isEmpty(f.getEma50Slope()) || f.getEma50Slope().compareTo(t.ema50SlopeMin) >= 0;
    }

    private boolean hasBearishEmaStack(MarketData md, FeatureStore f, Tuning t) {
        if (ObjectUtils.isEmpty(f.getEma50()) || ObjectUtils.isEmpty(f.getEma200())) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) >= 0) return false;
        if (f.getEma50().compareTo(f.getEma200()) >= 0) return false;
        return ObjectUtils.isEmpty(f.getEma50Slope()) || f.getEma50Slope().negate().compareTo(t.ema50SlopeMin) >= 0;
    }

    private boolean isAdxBandOk(FeatureStore f, Tuning t) {
        if (ObjectUtils.isEmpty(f.getAdx())) return false;
        return f.getAdx().compareTo(t.adxEntryMin) >= 0
                && f.getAdx().compareTo(t.adxEntryMax) <= 0;
    }

    private boolean isLongDiSpreadOk(FeatureStore f, Tuning t) {
        if (ObjectUtils.isEmpty(f.getPlusDI()) || ObjectUtils.isEmpty(f.getMinusDI())) return true;
        return f.getPlusDI().subtract(f.getMinusDI()).compareTo(t.diSpreadMin) >= 0;
    }

    private boolean isShortDiSpreadOk(FeatureStore f, Tuning t) {
        if (ObjectUtils.isEmpty(f.getPlusDI()) || ObjectUtils.isEmpty(f.getMinusDI())) return true;
        return f.getMinusDI().subtract(f.getPlusDI()).compareTo(t.diSpreadMin) >= 0;
    }

    private boolean isBullishBias(EnrichedStrategyContext ctx, Tuning t) {
        FeatureStore bias = ctx.getBiasFeatureStore();
        MarketData biasMd = ctx.getBiasMarketData();
        if (ObjectUtils.isEmpty(bias) || ObjectUtils.isEmpty(biasMd)) return false;
        boolean structure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasMd.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) > 0
                && biasMd.getClosePrice().compareTo(bias.getEma200()) > 0;
        return structure && isWithinBiasAdx(bias.getAdx(), t);
    }

    private boolean isBearishBias(EnrichedStrategyContext ctx, Tuning t) {
        FeatureStore bias = ctx.getBiasFeatureStore();
        MarketData biasMd = ctx.getBiasMarketData();
        if (ObjectUtils.isEmpty(bias) || ObjectUtils.isEmpty(biasMd)) return false;
        boolean structure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasMd.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) < 0
                && biasMd.getClosePrice().compareTo(bias.getEma200()) < 0;
        return structure && isWithinBiasAdx(bias.getAdx(), t);
    }

    private boolean isWithinBiasAdx(BigDecimal adx, Tuning t) {
        if (ObjectUtils.isEmpty(adx)) return false;
        return adx.compareTo(t.biasAdxMin) >= 0 && adx.compareTo(t.biasAdxMax) <= 0;
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    private StrategyDecision.StrategyDecisionBuilder baseBuilder(StrategySpec spec, EnrichedStrategyContext ctx) {
        String name = spec.getStrategyName();
        if (!StringUtils.hasText(name)) name = spec.getStrategyCode();
        Integer ver = spec.getArchetypeVersion();
        String version = ARCHETYPE_NAME + ".v" + (ObjectUtils.isEmpty(ver) ? VERSION : ver);
        return StrategyDecision.builder()
                .strategyCode(spec.getStrategyCode())
                .strategyName(name)
                .strategyVersion(version)
                .strategyInterval(ObjectUtils.isNotEmpty(ctx) ? ctx.getInterval() : null);
    }

    private StrategyDecision hold(StrategySpec spec, EnrichedStrategyContext ctx, String reason) {
        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.HOLD)
                .signalType(signalReclaim(spec)).reason(reason).decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", spec.getStrategyCode(), ARCHETYPE_NAME)).build();
    }

    private StrategyDecision veto(StrategySpec spec, EnrichedStrategyContext ctx, String reason) {
        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.HOLD)
                .vetoed(Boolean.TRUE).vetoReason(reason).reason("TPR vetoed").decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", spec.getStrategyCode(), ARCHETYPE_NAME, "RISK_LAYER"))
                .diagnostics(Map.of()).build();
    }

    // ── Spec-overridable label resolvers (body.signals.* takes precedence). ──
    private String signalReclaim(StrategySpec spec) {
        return spec.bodyString("signals.reclaimSignalType", DEFAULT_SIGNAL_RECLAIM);
    }
    private String setupLongReclaim(StrategySpec spec)  { return spec.bodyString("signals.setupLongReclaim",  DEFAULT_SETUP_LONG_RECLAIM); }
    private String setupShortReclaim(StrategySpec spec) { return spec.bodyString("signals.setupShortReclaim", DEFAULT_SETUP_SHORT_RECLAIM); }
    private String setupLongBe(StrategySpec spec)       { return spec.bodyString("signals.setupLongBe",       DEFAULT_SETUP_LONG_BE); }
    private String setupShortBe(StrategySpec spec)      { return spec.bodyString("signals.setupShortBe",      DEFAULT_SETUP_SHORT_BE); }
    private String setupLongTrail(StrategySpec spec)    { return spec.bodyString("signals.setupLongTrail",    DEFAULT_SETUP_LONG_TRAIL); }
    private String setupShortTrail(StrategySpec spec)   { return spec.bodyString("signals.setupShortTrail",   DEFAULT_SETUP_SHORT_TRAIL); }

    /** Snapshot of tuning values resolved from the spec. */
    private static final class Tuning {
        final BigDecimal ema50SlopeMin;
        final BigDecimal biasAdxMin;
        final BigDecimal biasAdxMax;
        final BigDecimal adxEntryMin;
        final BigDecimal adxEntryMax;
        final BigDecimal diSpreadMin;
        final BigDecimal pullbackTouchAtr;
        final BigDecimal longRsiMin;
        final BigDecimal longRsiMax;
        final BigDecimal shortRsiMin;
        final BigDecimal shortRsiMax;
        final BigDecimal bodyRatioMin;
        final BigDecimal clvMin;
        final BigDecimal clvMax;
        final BigDecimal rvolMin;
        final BigDecimal stopAtrBuffer;
        final BigDecimal maxEntryRiskPct;
        final BigDecimal tp1R;
        final BigDecimal breakEvenR;
        final BigDecimal runnerBreakEvenR;
        final BigDecimal runnerPhase2R;
        final BigDecimal runnerPhase3R;
        final BigDecimal runnerAtrPhase2;
        final BigDecimal runnerAtrPhase3;
        final BigDecimal runnerLockPhase2R;
        final BigDecimal runnerLockPhase3R;
        final BigDecimal minSignalScore;

        private Tuning(StrategySpec s) {
            this.ema50SlopeMin     = s.paramBigDecimal("ema50SlopeMin", D_EMA50_SLOPE_MIN);
            this.biasAdxMin        = s.paramBigDecimal("biasAdxMin", D_BIAS_ADX_MIN);
            this.biasAdxMax        = s.paramBigDecimal("biasAdxMax", D_BIAS_ADX_MAX);
            this.adxEntryMin       = s.paramBigDecimal("adxEntryMin", D_ADX_ENTRY_MIN);
            this.adxEntryMax       = s.paramBigDecimal("adxEntryMax", D_ADX_ENTRY_MAX);
            this.diSpreadMin       = s.paramBigDecimal("diSpreadMin", D_DI_SPREAD_MIN);
            this.pullbackTouchAtr  = s.paramBigDecimal("pullbackTouchAtr", D_PULLBACK_TOUCH_ATR);
            this.longRsiMin        = s.paramBigDecimal("longRsiMin", D_LONG_RSI_MIN);
            this.longRsiMax        = s.paramBigDecimal("longRsiMax", D_LONG_RSI_MAX);
            this.shortRsiMin       = s.paramBigDecimal("shortRsiMin", D_SHORT_RSI_MIN);
            this.shortRsiMax       = s.paramBigDecimal("shortRsiMax", D_SHORT_RSI_MAX);
            this.bodyRatioMin      = s.paramBigDecimal("bodyRatioMin", D_BODY_RATIO_MIN);
            this.clvMin            = s.paramBigDecimal("clvMin", D_CLV_MIN);
            this.clvMax            = s.paramBigDecimal("clvMax", D_CLV_MAX);
            this.rvolMin           = s.paramBigDecimal("rvolMin", D_RVOL_MIN);
            this.stopAtrBuffer     = s.paramBigDecimal("stopAtrBuffer", D_STOP_ATR_BUFFER);
            this.maxEntryRiskPct   = s.paramBigDecimal("maxEntryRiskPct", D_MAX_ENTRY_RISK_PCT);
            this.tp1R              = s.paramBigDecimal("tp1R", D_TP1_R);
            this.breakEvenR        = s.paramBigDecimal("breakEvenR", D_BREAK_EVEN_R);
            this.runnerBreakEvenR  = s.paramBigDecimal("runnerBreakEvenR", D_RUNNER_BE_R);
            this.runnerPhase2R     = s.paramBigDecimal("runnerPhase2R", D_RUNNER_PHASE2_R);
            this.runnerPhase3R     = s.paramBigDecimal("runnerPhase3R", D_RUNNER_PHASE3_R);
            this.runnerAtrPhase2   = s.paramBigDecimal("runnerAtrPhase2", D_RUNNER_ATR_PHASE2);
            this.runnerAtrPhase3   = s.paramBigDecimal("runnerAtrPhase3", D_RUNNER_ATR_PHASE3);
            this.runnerLockPhase2R = s.paramBigDecimal("runnerLockPhase2R", D_RUNNER_LOCK_PHASE2);
            this.runnerLockPhase3R = s.paramBigDecimal("runnerLockPhase3R", D_RUNNER_LOCK_PHASE3);
            this.minSignalScore    = s.paramBigDecimal("minSignalScore", D_MIN_SIGNAL_SCORE);
        }

        static Tuning from(StrategySpec spec) { return new Tuning(spec); }
    }
}
