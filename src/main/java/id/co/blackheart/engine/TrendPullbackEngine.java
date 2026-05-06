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

    public static final String ARCHETYPE = "trend_pullback";
    public static final int VERSION = 1;

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

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

    // Defaults — kept in sync with TPR Params.defaults() so spec.params={}
    // reproduces TPR. Any tuning change downstream goes through spec params,
    // not through edits to these constants.
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

    private final StrategyHelper strategyHelper;

    @Override public String archetype() { return ARCHETYPE; }
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
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(spec, context, "Invalid context");
        }
        Tuning t = Tuning.from(spec);
        MarketData md = context.getMarketData();
        FeatureStore f = context.getFeatureStore();
        PositionSnapshot snap = context.getPositionSnapshot();

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ZERO) <= 0) return hold(spec, context, "Invalid close price");

        if (EngineContextHelpers.isMarketVetoed(context)) return veto(spec, context, "Market vetoed");

        if (context.hasTradablePosition() && snap != null) {
            return managePosition(spec, context, md, f, snap, t);
        }

        if (context.isLongAllowed()) {
            StrategyDecision d = tryLongEntry(spec, context, md, f, t);
            if (d != null) return d;
        }
        if (context.isShortAllowed()) {
            StrategyDecision d = tryShortEntry(spec, context, md, f, t);
            if (d != null) return d;
        }
        return hold(spec, context, "No qualified TPR setup");
    }

    // ── Entries ──────────────────────────────────────────────────────────────

    private StrategyDecision tryLongEntry(StrategySpec spec, EnrichedStrategyContext ctx,
                                          MarketData md, FeatureStore f, Tuning t) {
        if (!isBullishBias(ctx, t)) return null;
        if (!hasBullishEmaStack(md, f, t)) return null;
        if (!isAdxBandOk(f, t)) return null;
        if (!isLongDiSpreadOk(f, t)) return null;

        BigDecimal atr = EngineContextHelpers.resolveAtr(f);
        if (atr == null) return null;
        BigDecimal ema20 = f.getEma20();
        if (ema20 == null) return null;

        BigDecimal low = strategyHelper.safe(md.getLowPrice());
        BigDecimal pullbackTol = atr.multiply(t.pullbackTouchAtr);
        if (low.subtract(ema20).compareTo(pullbackTol) > 0) return null;

        if (f.getRsi() == null
                || f.getRsi().compareTo(t.longRsiMin) < 0
                || f.getRsi().compareTo(t.longRsiMax) > 0) return null;

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ema20) <= 0) return null;
        if (f.getBodyToRangeRatio() == null
                || f.getBodyToRangeRatio().compareTo(t.bodyRatioMin) < 0) return null;
        if (f.getCloseLocationValue() == null
                || f.getCloseLocationValue().compareTo(t.clvMin) < 0
                || f.getCloseLocationValue().compareTo(t.clvMax) > 0) return null;
        if (f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(t.rvolMin) < 0) return null;

        BigDecimal entry = close;
        BigDecimal stop = low.subtract(atr.multiply(t.stopAtrBuffer));
        BigDecimal riskPerUnit = entry.subtract(stop);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entry.multiply(t.maxEntryRiskPct)) > 0) return null;

        BigDecimal tp1 = entry.add(riskPerUnit.multiply(t.tp1R));
        BigDecimal score = longSignalScore(f, t);
        if (score.compareTo(t.minSignalScore) < 0) return null;

        BigDecimal notional = strategyHelper.calculateEntryNotional(ctx, SIDE_LONG);
        if (notional.compareTo(ZERO) <= 0) return hold(spec, ctx, "TPR long notional zero");

        log.info("TPR[{}] LONG ENTRY | time={} entry={} stop={} tp1={} risk%={} score={}",
                spec.getStrategyCode(), md.getEndTime(), entry, stop, tp1,
                riskPerUnit.divide(entry, 4, RoundingMode.HALF_UP).multiply(HUNDRED), score);

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
                .tags(List.of("ENTRY", spec.getStrategyCode(), "LONG", ARCHETYPE))
                .diagnostics(Map.of("entry", entry, "stop", stop, "tp1", tp1,
                        "ema20", ema20, "atr", atr, "rsi", f.getRsi()))
                .build();
    }

    private StrategyDecision tryShortEntry(StrategySpec spec, EnrichedStrategyContext ctx,
                                           MarketData md, FeatureStore f, Tuning t) {
        if (!isBearishBias(ctx, t)) return null;
        if (!hasBearishEmaStack(md, f, t)) return null;
        if (!isAdxBandOk(f, t)) return null;
        if (!isShortDiSpreadOk(f, t)) return null;

        BigDecimal atr = EngineContextHelpers.resolveAtr(f);
        if (atr == null) return null;
        BigDecimal ema20 = f.getEma20();
        if (ema20 == null) return null;

        BigDecimal high = strategyHelper.safe(md.getHighPrice());
        BigDecimal pullbackTol = atr.multiply(t.pullbackTouchAtr);
        if (ema20.subtract(high).compareTo(pullbackTol) > 0) return null;

        if (f.getRsi() == null
                || f.getRsi().compareTo(t.shortRsiMin) < 0
                || f.getRsi().compareTo(t.shortRsiMax) > 0) return null;

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ema20) >= 0) return null;
        if (f.getBodyToRangeRatio() == null
                || f.getBodyToRangeRatio().compareTo(t.bodyRatioMin) < 0) return null;

        BigDecimal clv = f.getCloseLocationValue();
        BigDecimal clvShortMax = ONE.subtract(t.clvMin);
        BigDecimal clvShortMin = ONE.subtract(t.clvMax);
        if (clv == null || clv.compareTo(clvShortMax) > 0 || clv.compareTo(clvShortMin) < 0) return null;

        if (f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(t.rvolMin) < 0) return null;

        BigDecimal entry = close;
        BigDecimal stop = high.add(atr.multiply(t.stopAtrBuffer));
        BigDecimal riskPerUnit = stop.subtract(entry);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entry.multiply(t.maxEntryRiskPct)) > 0) return null;

        BigDecimal tp1 = entry.subtract(riskPerUnit.multiply(t.tp1R));
        BigDecimal score = shortSignalScore(f, t);
        if (score.compareTo(t.minSignalScore) < 0) return null;

        BigDecimal positionSize = strategyHelper.calculateShortPositionSize(ctx);
        if (positionSize.compareTo(ZERO) <= 0) return hold(spec, ctx, "TPR short position size zero");

        log.info("TPR[{}] SHORT ENTRY | time={} entry={} stop={} tp1={} risk%={} score={}",
                spec.getStrategyCode(), md.getEndTime(), entry, stop, tp1,
                riskPerUnit.divide(entry, 4, RoundingMode.HALF_UP).multiply(HUNDRED), score);

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
                .tags(List.of("ENTRY", spec.getStrategyCode(), "SHORT", ARCHETYPE))
                .diagnostics(Map.of("entry", entry, "stop", stop, "tp1", tp1,
                        "ema20", ema20, "atr", atr, "rsi", f.getRsi()))
                .build();
    }

    // ── Position management ──────────────────────────────────────────────────

    private StrategyDecision managePosition(StrategySpec spec, EnrichedStrategyContext ctx,
                                            MarketData md, FeatureStore f,
                                            PositionSnapshot snap, Tuning t) {
        String side = snap.getSide();
        if (side == null) return hold(spec, ctx, "TPR manage: unknown side");
        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);
        boolean isRunner = POSITION_ROLE_RUNNER.equalsIgnoreCase(snap.getPositionRole());

        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal initStop = snap.getInitialStopLossPrice() != null
                ? snap.getInitialStopLossPrice() : curStop;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal initRisk = isLong ? entry.subtract(initStop) : initStop.subtract(entry);
        if (initRisk.compareTo(ZERO) <= 0) return hold(spec, ctx, "TPR manage: invalid init risk");

        BigDecimal move = isLong ? close.subtract(entry) : entry.subtract(close);
        if (move.compareTo(ZERO) <= 0) return hold(spec, ctx, "TPR manage: not in profit");
        BigDecimal rMultiple = move.divide(initRisk, 8, RoundingMode.HALF_UP);

        if (!isRunner) {
            if (move.compareTo(initRisk.multiply(t.breakEvenR)) < 0) {
                return hold(spec, ctx, "TPR BE not ready");
            }
            boolean alreadyAtBe = isLong ? curStop.compareTo(entry) >= 0 : curStop.compareTo(entry) <= 0;
            if (alreadyAtBe) return hold(spec, ctx, "TPR stop already at BE");

            return baseBuilder(spec, ctx)
                    .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                    .signalType(SIGNAL_TYPE_MANAGEMENT)
                    .setupType(isLong ? setupLongBe(spec) : setupShortBe(spec)).side(side)
                    .stopLossPrice(entry).takeProfitPrice1(snap.getTakeProfitPrice())
                    .targetPositionRole(POSITION_ROLE_TP1)
                    .reason("Move TP1 leg to break-even at " + t.breakEvenR + "R")
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("MANAGEMENT", spec.getStrategyCode(), side, "BREAK_EVEN"))
                    .diagnostics(Map.of("entry", entry, "curStop", curStop,
                            "close", close, "rMultiple", rMultiple))
                    .build();
        }

        // Runner phase trail
        BigDecimal atr = EngineContextHelpers.resolveAtr(f);
        if (atr == null) return hold(spec, ctx, "TPR runner: no ATR");
        BigDecimal candidate = null;

        if (rMultiple.compareTo(t.runnerPhase3R) >= 0) {
            BigDecimal trail = isLong
                    ? close.subtract(atr.multiply(t.runnerAtrPhase3))
                    : close.add(atr.multiply(t.runnerAtrPhase3));
            BigDecimal lock = isLong
                    ? entry.add(initRisk.multiply(t.runnerLockPhase3R))
                    : entry.subtract(initRisk.multiply(t.runnerLockPhase3R));
            candidate = isLong ? trail.max(lock) : trail.min(lock);
        } else if (rMultiple.compareTo(t.runnerPhase2R) >= 0) {
            BigDecimal trail = isLong
                    ? close.subtract(atr.multiply(t.runnerAtrPhase2))
                    : close.add(atr.multiply(t.runnerAtrPhase2));
            BigDecimal lock = isLong
                    ? entry.add(initRisk.multiply(t.runnerLockPhase2R))
                    : entry.subtract(initRisk.multiply(t.runnerLockPhase2R));
            candidate = isLong ? trail.max(lock) : trail.min(lock);
        } else if (rMultiple.compareTo(t.runnerBreakEvenR) >= 0) {
            boolean alreadyAtBe = isLong ? curStop.compareTo(entry) >= 0 : curStop.compareTo(entry) <= 0;
            if (!alreadyAtBe) candidate = entry;
        }

        if (candidate == null) return hold(spec, ctx, "TPR runner not ready");
        boolean improved = isLong
                ? candidate.compareTo(curStop) > 0
                : candidate.compareTo(curStop) < 0;
        if (!improved) return hold(spec, ctx, "TPR runner stop already optimal");

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(isLong ? setupLongTrail(spec) : setupShortTrail(spec)).side(side)
                .stopLossPrice(candidate).trailingStopPrice(candidate)
                .takeProfitPrice1(snap.getTakeProfitPrice())
                .targetPositionRole(POSITION_ROLE_RUNNER)
                .reason("TPR runner trail at " + rMultiple + "R")
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", spec.getStrategyCode(), side, "TRAIL"))
                .diagnostics(Map.of("rMultiple", rMultiple, "candidate", candidate,
                        "curStop", curStop, "close", close, "atr", atr))
                .build();
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    private BigDecimal longSignalScore(FeatureStore f, Tuning t) {
        BigDecimal rsi = f.getRsi() != null ? normalise(f.getRsi(), t.longRsiMin, t.longRsiMax) : ZERO;
        BigDecimal clv = f.getCloseLocationValue() != null ? f.getCloseLocationValue() : ZERO;
        BigDecimal body = f.getBodyToRangeRatio() != null ? f.getBodyToRangeRatio() : ZERO;
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
        BigDecimal body = f.getBodyToRangeRatio() != null ? f.getBodyToRangeRatio() : ZERO;
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
        if (value == null || lo == null || hi == null) return ZERO;
        BigDecimal range = hi.subtract(lo);
        if (range.compareTo(ZERO) <= 0) return ZERO;
        BigDecimal scaled = value.subtract(lo).divide(range, 8, RoundingMode.HALF_UP);
        if (scaled.compareTo(ZERO) < 0) return ZERO;
        if (scaled.compareTo(ONE) > 0) return ONE;
        return scaled;
    }

    // ── Gating helpers ───────────────────────────────────────────────────────

    private boolean hasBullishEmaStack(MarketData md, FeatureStore f, Tuning t) {
        if (f.getEma50() == null || f.getEma200() == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) <= 0) return false;
        if (f.getEma50().compareTo(f.getEma200()) <= 0) return false;
        return f.getEma50Slope() == null || f.getEma50Slope().compareTo(t.ema50SlopeMin) >= 0;
    }

    private boolean hasBearishEmaStack(MarketData md, FeatureStore f, Tuning t) {
        if (f.getEma50() == null || f.getEma200() == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) >= 0) return false;
        if (f.getEma50().compareTo(f.getEma200()) >= 0) return false;
        return f.getEma50Slope() == null || f.getEma50Slope().negate().compareTo(t.ema50SlopeMin) >= 0;
    }

    private boolean isAdxBandOk(FeatureStore f, Tuning t) {
        if (f.getAdx() == null) return false;
        return f.getAdx().compareTo(t.adxEntryMin) >= 0
                && f.getAdx().compareTo(t.adxEntryMax) <= 0;
    }

    private boolean isLongDiSpreadOk(FeatureStore f, Tuning t) {
        if (f.getPlusDI() == null || f.getMinusDI() == null) return true;
        return f.getPlusDI().subtract(f.getMinusDI()).compareTo(t.diSpreadMin) >= 0;
    }

    private boolean isShortDiSpreadOk(FeatureStore f, Tuning t) {
        if (f.getPlusDI() == null || f.getMinusDI() == null) return true;
        return f.getMinusDI().subtract(f.getPlusDI()).compareTo(t.diSpreadMin) >= 0;
    }

    private boolean isBullishBias(EnrichedStrategyContext ctx, Tuning t) {
        FeatureStore bias = ctx.getBiasFeatureStore();
        MarketData biasMd = ctx.getBiasMarketData();
        if (bias == null || biasMd == null) return false;
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
        if (bias == null || biasMd == null) return false;
        boolean structure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasMd.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) < 0
                && biasMd.getClosePrice().compareTo(bias.getEma200()) < 0;
        return structure && isWithinBiasAdx(bias.getAdx(), t);
    }

    private boolean isWithinBiasAdx(BigDecimal adx, Tuning t) {
        if (adx == null) return false;
        return adx.compareTo(t.biasAdxMin) >= 0 && adx.compareTo(t.biasAdxMax) <= 0;
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    private StrategyDecision.StrategyDecisionBuilder baseBuilder(StrategySpec spec, EnrichedStrategyContext ctx) {
        String name = spec.getStrategyName();
        if (!StringUtils.hasText(name)) name = spec.getStrategyCode();
        Integer ver = spec.getArchetypeVersion();
        String version = ARCHETYPE + ".v" + (ver == null ? VERSION : ver);
        return StrategyDecision.builder()
                .strategyCode(spec.getStrategyCode())
                .strategyName(name)
                .strategyVersion(version)
                .strategyInterval(ctx != null ? ctx.getInterval() : null);
    }

    private StrategyDecision hold(StrategySpec spec, EnrichedStrategyContext ctx, String reason) {
        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.HOLD)
                .signalType(signalReclaim(spec)).reason(reason).decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", spec.getStrategyCode(), ARCHETYPE)).build();
    }

    private StrategyDecision veto(StrategySpec spec, EnrichedStrategyContext ctx, String reason) {
        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.HOLD)
                .vetoed(Boolean.TRUE).vetoReason(reason).reason("TPR vetoed").decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", spec.getStrategyCode(), ARCHETYPE, "RISK_LAYER"))
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
        final BigDecimal biasAdxMin, biasAdxMax;
        final BigDecimal adxEntryMin, adxEntryMax, diSpreadMin;
        final BigDecimal pullbackTouchAtr;
        final BigDecimal longRsiMin, longRsiMax, shortRsiMin, shortRsiMax;
        final BigDecimal bodyRatioMin, clvMin, clvMax, rvolMin;
        final BigDecimal stopAtrBuffer, maxEntryRiskPct, tp1R;
        final BigDecimal breakEvenR;
        final BigDecimal runnerBreakEvenR, runnerPhase2R, runnerPhase3R;
        final BigDecimal runnerAtrPhase2, runnerAtrPhase3;
        final BigDecimal runnerLockPhase2R, runnerLockPhase3R;
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
