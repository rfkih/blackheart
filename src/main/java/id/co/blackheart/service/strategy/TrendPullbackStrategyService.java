package id.co.blackheart.service.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Trend Pullback Reclaim (TPR) — research v0.
 *
 * <p>Thesis: in a confirmed trend, shallow mean-reversion pullbacks to the
 * 20-period EMA that <em>recover</em> on strong volume and a bullish close
 * are a reliable entry. The edge comes from combining (a) a multi-EMA trend
 * filter, (b) an ADX floor that confirms the trend is real, (c) a pullback
 * contact with EMA20, and (d) a reclaim candle (close > EMA20, high-CLV, body).
 * Stop goes below the pullback low with an ATR buffer; TP1 is a fixed
 * R-multiple; the runner trails by ATR in phases after break-even.
 *
 * <p>Everything — params, defaults, and full logic — lives in this file by
 * design (research iteration). Overrides aren't persisted: tweak the
 * constants in {@link Params#defaults()}, restart, re-run.
 *
 * <p>Wire-up: registered in {@link StrategyExecutorFactory} under code {@code "TPR"}.
 */
@Service
@Slf4j
public class TrendPullbackStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;
    private final ResearchParamService researchParamService;
    private final ObjectMapper objectMapper;

    /**
     * @{@code @Lazy} on {@link ResearchParamService} breaks a potential
     * circular import chain (research → backtest → strategy → research). The
     * proxy resolves on first call — well after app context is up.
     */
    public TrendPullbackStrategyService(
            StrategyHelper strategyHelper,
            @Lazy @Autowired ResearchParamService researchParamService,
            ObjectMapper objectMapper
    ) {
        this.strategyHelper = strategyHelper;
        this.researchParamService = researchParamService;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve effective params for the current evaluation:
     * base params from {@link ResearchParamService} (hot-reloadable), optionally
     * overlaid with per-run overrides from {@link BacktestParamOverrideContext}.
     *
     * <p>Uses explicit setters (mirrors the {@code VcbParams.merge} /
     * {@code LsrParams.merge} pattern) rather than a Jackson convertValue
     * round-trip — the round-trip was dropping overrides silently in the sweep
     * pipeline because BigDecimal inner-map values deserialised as Double and
     * the re-bind to Params could lose precision on rare grid values. Explicit
     * setters are verbose but never lose an override.
     */
    private Params resolveParams() {
        Params base = researchParamService.getTprParams();
        Map<String, Object> overrides = BacktestParamOverrideContext.forStrategy(STRATEGY_CODE);
        if (CollectionUtils.isEmpty(overrides)) {
            return base;
        }

        // Clone the base by JSON round-trip through ObjectMapper so we never
        // mutate the shared store-owned reference on the hot path.
        Params p;
        try {
            p = objectMapper.readValue(objectMapper.writeValueAsString(base), Params.class);
        } catch (Exception e) {
            log.error("Failed to clone TPR baseline — using shared reference", e);
            p = base;
        }

        // Apply each override explicitly. Unrecognised keys are logged and
        // ignored so typos don't nuke a sweep, and the log shows exactly
        // which fields the sweep changed per combo.
        int applied = 0;
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            BigDecimal v = toDecimal(e.getValue());
            if (v == null) continue;
            boolean took = applyOverride(p, e.getKey(), v);
            if (took) applied++;
        }
        log.info("TPR overrides applied | keys={} applied={} snapshot=[adxMin={}, adxMax={}, clvMin={}, clvMax={}, stopBuf={}, rvolMin={}, tp1R={}]",
                overrides.keySet(), applied,
                p.getAdxEntryMin(), p.getAdxEntryMax(), p.getClvMin(), p.getClvMax(),
                p.getStopAtrBuffer(), p.getRvolMin(), p.getTp1R());
        return p;
    }

    /** Field-by-field setter dispatcher — returns true when the key matched a
     *  known Params field. Anything unknown is logged and dropped. */
    private boolean applyOverride(Params p, String key, BigDecimal v) {
        switch (key) {
            case "ema50SlopeMin" -> p.setEma50SlopeMin(v);
            case "biasAdxMin" -> p.setBiasAdxMin(v);
            case "biasAdxMax" -> p.setBiasAdxMax(v);
            case "adxEntryMin" -> p.setAdxEntryMin(v);
            case "adxEntryMax" -> p.setAdxEntryMax(v);
            case "diSpreadMin" -> p.setDiSpreadMin(v);
            case "pullbackTouchAtr" -> p.setPullbackTouchAtr(v);
            case "longRsiMin" -> p.setLongRsiMin(v);
            case "longRsiMax" -> p.setLongRsiMax(v);
            case "shortRsiMin" -> p.setShortRsiMin(v);
            case "shortRsiMax" -> p.setShortRsiMax(v);
            case "bodyRatioMin" -> p.setBodyRatioMin(v);
            case "clvMin" -> p.setClvMin(v);
            case "clvMax" -> p.setClvMax(v);
            case "rvolMin" -> p.setRvolMin(v);
            case "stopAtrBuffer" -> p.setStopAtrBuffer(v);
            case "maxEntryRiskPct" -> p.setMaxEntryRiskPct(v);
            case "tp1R" -> p.setTp1R(v);
            case "riskPerTradePct" -> p.setRiskPerTradePct(v);
            case "maxAllocationPct" -> p.setMaxAllocationPct(v);
            case "breakEvenR" -> p.setBreakEvenR(v);
            case "runnerBreakEvenR" -> p.setRunnerBreakEvenR(v);
            case "runnerPhase2R" -> p.setRunnerPhase2R(v);
            case "runnerPhase3R" -> p.setRunnerPhase3R(v);
            case "runnerAtrPhase2" -> p.setRunnerAtrPhase2(v);
            case "runnerAtrPhase3" -> p.setRunnerAtrPhase3(v);
            case "runnerLockPhase2R" -> p.setRunnerLockPhase2R(v);
            case "runnerLockPhase3R" -> p.setRunnerLockPhase3R(v);
            case "minSignalScore" -> p.setMinSignalScore(v);
            default -> {
                log.warn("TPR override key ignored — unknown field: {}", key);
                return false;
            }
        }
        return true;
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    // ── Identity ──────────────────────────────────────────────────────────────
    private static final String STRATEGY_CODE    = "TPR";
    private static final String STRATEGY_NAME    = "Trend Pullback Reclaim";
    private static final String STRATEGY_VERSION = "v0_4_research";

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_RECLAIM    = "TREND_PULLBACK_RECLAIM";
    private static final String SIGNAL_TYPE_MANAGEMENT = "POSITION_MANAGEMENT";

    private static final String SETUP_LONG_RECLAIM  = "TPR_LONG_RECLAIM";
    private static final String SETUP_SHORT_RECLAIM = "TPR_SHORT_RECLAIM";
    private static final String SETUP_LONG_BE       = "TPR_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BE      = "TPR_SHORT_BREAK_EVEN";
    private static final String SETUP_LONG_TRAIL    = "TPR_LONG_TRAIL";
    private static final String SETUP_SHORT_TRAIL   = "TPR_SHORT_TRAIL";

    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String TARGET_ALL = "ALL";
    private static final String POSITION_ROLE_TP1    = "TP1";
    private static final String POSITION_ROLE_RUNNER = "RUNNER";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Diagnostic key carrying the entry price on every TPR decision row. */
    private static final String DIAG_ENTRY = "entry";

    // ── StrategyExecutor contract ─────────────────────────────────────────────

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

        MarketData md = context.getMarketData();
        FeatureStore f = context.getFeatureStore();
        PositionSnapshot snap = context.getPositionSnapshot();
        // Effective params = research-store baseline + optional per-run
        // overrides from BacktestParamOverrideContext (wizard / sweep driver).
        Params p = resolveParams();

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ZERO) <= 0) return hold(context, "Invalid close price");

        if (isMarketVetoed(context)) {
            return veto("Market vetoed by quality / jump-risk filter", context);
        }

        // Already in a trade → run management branch (BE / trail).
        if (context.hasTradablePosition() && snap != null) {
            return managePosition(context, md, f, snap, p);
        }

        if (context.isLongAllowed()) {
            StrategyDecision d = tryLongEntry(context, md, f, p);
            if (d != null) return d;
        }

        if (context.isShortAllowed()) {
            StrategyDecision d = tryShortEntry(context, md, f, p);
            if (d != null) return d;
        }

        return hold(context, "No qualified TPR setup");
    }

    // ── Entries ───────────────────────────────────────────────────────────────

    private StrategyDecision tryLongEntry(
            EnrichedStrategyContext ctx, MarketData md, @NonNull FeatureStore f, Params p
    ) {
        if (!passesLongTrendGates(ctx, md, f, p)) return null;

        BigDecimal atr = resolveAtr(f);
        BigDecimal ema20 = f.getEma20();
        if (ema20 == null) return null;
        BigDecimal low = strategyHelper.safe(md.getLowPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());

        if (!passesLongCandleGates(f, low, close, ema20, atr, p)) return null;

        EntrySizing sizing = computeLongSizing(close, low, atr, p);
        if (sizing == null) return null;

        BigDecimal score = calculateLongSignalScore(f, p);
        if (score.compareTo(p.getMinSignalScore()) < 0) {
            log.debug("TPR LONG score FAIL score={} min={}", score, p.getMinSignalScore());
            return null;
        }

        BigDecimal notional = pickLongNotional(ctx, sizing.entry(), sizing.stop(), p);
        if (notional.compareTo(ZERO) <= 0) return hold(ctx, "TPR long notional zero");

        // stopDist% is the stop distance from entry as % (the strategy's
        // structural risk-per-unit). Distinct from `riskPerTradePct` which
        // is the % of capital risked per trade — the V2 sizing knob.
        log.info("TPR LONG ENTRY | time={} close={} ema20={} stop={} tp1={} stopDist%={} riskPerTradePct={} notional={} score={}",
                md.getEndTime(), sizing.entry(), ema20, sizing.stop(), sizing.tp1(),
                sizing.riskPerUnit().divide(sizing.entry(), 4, RoundingMode.HALF_UP).multiply(HUNDRED),
                p.getRiskPerTradePct(), notional, score);

        return buildLongEntryDecision(ctx, f, ema20, atr, sizing, score, notional);
    }

    /** Trend / momentum environment: bias HTF, EMA stack, ADX band, DI spread.
     *  Logs the failing gate at DEBUG so signal forensics survive the
     *  decomposition. */
    private boolean passesLongTrendGates(EnrichedStrategyContext ctx, MarketData md, @NonNull FeatureStore f, Params p) {
        if (!isBullishBias(ctx, p)) {
            log.debug("TPR LONG gate-bias FAIL");
            return false;
        }
        if (!hasBullishEmaStack(md, f, p)) {
            log.debug("TPR LONG gate-stack FAIL close={} ema50={} ema200={} slope={}",
                    md.getClosePrice(), f.getEma50(), f.getEma200(), f.getEma50Slope());
            return false;
        }
        if (f.getAdx() == null
                || f.getAdx().compareTo(p.getAdxEntryMin()) < 0
                || f.getAdx().compareTo(p.getAdxEntryMax()) > 0) {
            log.debug("TPR LONG gate-adx FAIL adx={} min={} max={}",
                    f.getAdx(), p.getAdxEntryMin(), p.getAdxEntryMax());
            return false;
        }
        if (f.getPlusDI() != null && f.getMinusDI() != null) {
            BigDecimal diSpread = f.getPlusDI().subtract(f.getMinusDI());
            if (diSpread.compareTo(p.getDiSpreadMin()) < 0) {
                log.debug("TPR LONG gate-di FAIL spread={}", diSpread);
                return false;
            }
        }
        return true;
    }

    /** Pullback + reclaim-candle gates: bar touches EMA20 band, RSI in band,
     *  close reclaims above EMA20 with body / CLV / volume confirmation. */
    private boolean passesLongCandleGates(@NonNull FeatureStore f,
                                          BigDecimal low, BigDecimal close, BigDecimal ema20,
                                          BigDecimal atr, Params p) {
        BigDecimal distanceToEma = low.subtract(ema20);
        BigDecimal pullbackTol = atr.multiply(p.getPullbackTouchAtr());
        if (distanceToEma.compareTo(pullbackTol) > 0) {
            log.debug("TPR LONG gate-pullback FAIL low={} ema20={} distance={} tol={}",
                    low, ema20, distanceToEma, pullbackTol);
            return false;
        }
        if (f.getRsi() == null
                || f.getRsi().compareTo(p.getLongRsiMin()) < 0
                || f.getRsi().compareTo(p.getLongRsiMax()) > 0) {
            log.debug("TPR LONG gate-rsi FAIL rsi={} band={}/{}",
                    f.getRsi(), p.getLongRsiMin(), p.getLongRsiMax());
            return false;
        }
        if (close.compareTo(ema20) <= 0) {
            log.debug("TPR LONG gate-reclaim FAIL close={} <= ema20={}", close, ema20);
            return false;
        }
        if (f.getBodyToRangeRatio() == null
                || f.getBodyToRangeRatio().compareTo(p.getBodyRatioMin()) < 0) {
            log.debug("TPR LONG gate-body FAIL body={}", f.getBodyToRangeRatio());
            return false;
        }
        // CLV upper bound rejects bars that closed ON the high (CLV >= 0.90)
        // — v0.1 data showed 7/7 such trades lost, classic late-in-move
        // exhaustion signature dressed up as a strong candle.
        if (f.getCloseLocationValue() == null
                || f.getCloseLocationValue().compareTo(p.getClvMin()) < 0
                || f.getCloseLocationValue().compareTo(p.getClvMax()) > 0) {
            log.debug("TPR LONG gate-clv FAIL clv={} band={}/{}",
                    f.getCloseLocationValue(), p.getClvMin(), p.getClvMax());
            return false;
        }
        if (f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(p.getRvolMin()) < 0) {
            log.debug("TPR LONG gate-rvol FAIL rvol={}", f.getRelativeVolume20());
            return false;
        }
        return true;
    }

    /** Entry / stop / TP1 / risk-per-unit bundle resolved from a passed-gate
     *  reclaim. */
    private record EntrySizing(BigDecimal entry, BigDecimal stop, BigDecimal tp1, BigDecimal riskPerUnit) {}

    /** Structural LONG sizing: stop a configurable ATR buffer below the bar
     *  low. Returns null when riskPerUnit is non-positive (degenerate setup)
     *  or when the stop is wider than {@code maxEntryRiskPct} of the entry. */
    private EntrySizing computeLongSizing(BigDecimal close, BigDecimal low, BigDecimal atr, Params p) {
        BigDecimal entry = close;
        BigDecimal stop = low.subtract(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = entry.subtract(stop);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal maxAllowedRisk = entry.multiply(p.getMaxEntryRiskPct());
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) {
            log.debug("TPR LONG skipped — stop too wide risk={}%",
                    riskPerUnit.divide(entry, 4, RoundingMode.HALF_UP).multiply(HUNDRED));
            return null;
        }
        BigDecimal tp1 = entry.add(riskPerUnit.multiply(p.getTp1R()));
        return new EntrySizing(entry, stop, tp1, riskPerUnit);
    }

    /** LONG notional resolution: risk-based when riskPerTradePct is set (V2
     *  default), else legacy capital-allocation. Returned value may be ZERO
     *  when both paths produce no capital — caller emits a HOLD. */
    private BigDecimal pickLongNotional(EnrichedStrategyContext ctx, BigDecimal entry, BigDecimal stop, Params p) {
        BigDecimal notional = strategyHelper.calculateRiskBasedNotional(
                ctx, entry, stop, p.getRiskPerTradePct(), p.getMaxAllocationPct());
        if (notional.compareTo(ZERO) <= 0) {
            notional = strategyHelper.calculateEntryNotional(ctx, SIDE_LONG);
        }
        return notional;
    }

    private StrategyDecision buildLongEntryDecision(
            EnrichedStrategyContext ctx, @NonNull FeatureStore f, BigDecimal ema20, BigDecimal atr,
            EntrySizing sizing, BigDecimal score, BigDecimal notional) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_RECLAIM)
                .setupType(SETUP_LONG_RECLAIM)
                .side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(ctx, f))
                .reason("TPR long: pullback to EMA20 reclaimed on body+CLV+volume in confirmed uptrend")
                .signalScore(score)
                .confidenceScore(score)
                .regimeScore(resolveRegimeScore(ctx))
                .riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .notionalSize(notional)
                .stopLossPrice(sizing.stop())
                .trailingStopPrice(null)
                .takeProfitPrice1(sizing.tp1())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_TP1_RUNNER)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx())
                .entryAtr(f.getAtr())
                .entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "TPR", SIDE_LONG, "RECLAIM", STRATEGY_VERSION))
                .diagnostics(Map.of(
                        "module", "TrendPullbackStrategyService",
                        DIAG_ENTRY, sizing.entry(), "stop", sizing.stop(), "tp1", sizing.tp1(),
                        "ema20", ema20, "atr", atr, "rsi", f.getRsi()))
                .build();
    }

    private StrategyDecision tryShortEntry(
            EnrichedStrategyContext ctx, MarketData md, @NonNull FeatureStore f, Params p
    ) {
        if (!passesShortTrendGates(ctx, md, f, p)) return null;

        BigDecimal atr = resolveAtr(f);
        BigDecimal ema20 = f.getEma20();
        if (ema20 == null) return null;
        BigDecimal high = strategyHelper.safe(md.getHighPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());

        if (!passesShortCandleGates(f, high, close, ema20, atr, p)) return null;

        EntrySizing sizing = computeShortSizing(close, high, atr, p);
        if (sizing == null) return null;

        BigDecimal score = calculateShortSignalScore(f, p);
        if (score.compareTo(p.getMinSignalScore()) < 0) return null;

        BigDecimal positionSize = pickShortPositionSize(ctx, sizing.entry(), sizing.stop(), p);
        if (positionSize.compareTo(ZERO) <= 0) return hold(ctx, "TPR short position size zero");

        log.info("TPR SHORT ENTRY | time={} close={} ema20={} stop={} tp1={} stopDist%={} riskPerTradePct={} positionSize={} score={}",
                md.getEndTime(), sizing.entry(), ema20, sizing.stop(), sizing.tp1(),
                sizing.riskPerUnit().divide(sizing.entry(), 4, RoundingMode.HALF_UP).multiply(HUNDRED),
                p.getRiskPerTradePct(), positionSize, score);

        return buildShortEntryDecision(ctx, f, ema20, atr, sizing, score, positionSize);
    }

    /** Trend / momentum environment for the SHORT side: bearish bias, EMA
     *  stack, ADX band, DI spread on the bear side. Mirrors
     *  {@link #passesLongTrendGates}. */
    private boolean passesShortTrendGates(EnrichedStrategyContext ctx, MarketData md, @NonNull FeatureStore f, Params p) {
        if (!isBearishBias(ctx, p)) {
            log.debug("TPR SHORT gate-bias FAIL");
            return false;
        }
        if (!hasBearishEmaStack(md, f, p)) {
            log.debug("TPR SHORT gate-stack FAIL");
            return false;
        }
        if (f.getAdx() == null
                || f.getAdx().compareTo(p.getAdxEntryMin()) < 0
                || f.getAdx().compareTo(p.getAdxEntryMax()) > 0) {
            return false;
        }
        if (f.getPlusDI() != null && f.getMinusDI() != null) {
            BigDecimal diSpread = f.getMinusDI().subtract(f.getPlusDI());
            if (diSpread.compareTo(p.getDiSpreadMin()) < 0) return false;
        }
        return true;
    }

    /** Pullback + rejection-candle gates for SHORT entries. CLV band is
     *  mirrored from the LONG band — close must be near the bar's low but
     *  not pinned to it (CLV == 0 is just as over-extended as CLV == 1 on
     *  the long side). */
    private boolean passesShortCandleGates(@NonNull FeatureStore f, BigDecimal high, BigDecimal close,
                                           BigDecimal ema20, BigDecimal atr, Params p) {
        BigDecimal distanceToEma = ema20.subtract(high);
        BigDecimal pullbackTol = atr.multiply(p.getPullbackTouchAtr());
        if (distanceToEma.compareTo(pullbackTol) > 0) return false;

        if (f.getRsi() == null
                || f.getRsi().compareTo(p.getShortRsiMin()) < 0
                || f.getRsi().compareTo(p.getShortRsiMax()) > 0) {
            return false;
        }
        if (close.compareTo(ema20) >= 0) return false;

        if (f.getBodyToRangeRatio() == null
                || f.getBodyToRangeRatio().compareTo(p.getBodyRatioMin()) < 0) {
            return false;
        }
        BigDecimal clv = f.getCloseLocationValue();
        BigDecimal clvShortMax = ONE.subtract(p.getClvMin());
        BigDecimal clvShortMin = ONE.subtract(p.getClvMax());
        if (clv == null || clv.compareTo(clvShortMax) > 0 || clv.compareTo(clvShortMin) < 0) {
            log.debug("TPR SHORT gate-clv FAIL clv={} band={}/{}",
                    clv, clvShortMin, clvShortMax);
            return false;
        }
        return f.getRelativeVolume20() != null
                && f.getRelativeVolume20().compareTo(p.getRvolMin()) >= 0;
    }

    /** Structural SHORT sizing: stop a configurable ATR buffer above the bar
     *  high. Returns null when riskPerUnit is non-positive (degenerate setup)
     *  or when the stop is wider than {@code maxEntryRiskPct} of the entry. */
    private EntrySizing computeShortSizing(BigDecimal close, BigDecimal high, BigDecimal atr, Params p) {
        BigDecimal entry = close;
        BigDecimal stop = high.add(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = stop.subtract(entry);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        if (riskPerUnit.compareTo(entry.multiply(p.getMaxEntryRiskPct())) > 0) return null;
        BigDecimal tp1 = entry.subtract(riskPerUnit.multiply(p.getTp1R()));
        return new EntrySizing(entry, stop, tp1, riskPerUnit);
    }

    /** SHORT executor reads {@code positionSize} (BTC qty). Try risk-based
     *  sizing first — the helper returns USDT notional which we convert to
     *  BTC qty by dividing by entry price. Falls back to the legacy
     *  BTC-allocation path when riskPerTradePct is unset or the inputs are
     *  degenerate. */
    private BigDecimal pickShortPositionSize(EnrichedStrategyContext ctx, BigDecimal entry, BigDecimal stop, Params p) {
        BigDecimal riskNotionalUsdt = strategyHelper.calculateRiskBasedNotional(
                ctx, entry, stop, p.getRiskPerTradePct(), p.getMaxAllocationPct());
        if (riskNotionalUsdt.compareTo(ZERO) > 0) {
            return riskNotionalUsdt.divide(entry, 8, RoundingMode.HALF_UP);
        }
        return strategyHelper.calculateShortPositionSize(ctx);
    }

    private StrategyDecision buildShortEntryDecision(
            EnrichedStrategyContext ctx, @NonNull FeatureStore f, BigDecimal ema20, BigDecimal atr,
            EntrySizing sizing, BigDecimal score, BigDecimal positionSize) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_RECLAIM)
                .setupType(SETUP_SHORT_RECLAIM)
                .side(SIDE_SHORT)
                .regimeLabel(resolveRegimeLabel(ctx, f))
                .reason("TPR short: rally to EMA20 rejected on body+CLV+volume in confirmed downtrend")
                .signalScore(score)
                .confidenceScore(score)
                .regimeScore(resolveRegimeScore(ctx))
                .riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .positionSize(positionSize)
                .stopLossPrice(sizing.stop())
                .trailingStopPrice(null)
                .takeProfitPrice1(sizing.tp1())
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_TP1_RUNNER)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx())
                .entryAtr(f.getAtr())
                .entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "TPR", SIDE_SHORT, "RECLAIM", STRATEGY_VERSION))
                .diagnostics(Map.of(
                        "module", "TrendPullbackStrategyService",
                        DIAG_ENTRY, sizing.entry(), "stop", sizing.stop(), "tp1", sizing.tp1(),
                        "ema20", ema20, "atr", atr, "rsi", f.getRsi()))
                .build();
    }

    // ── Position management ───────────────────────────────────────────────────

    private StrategyDecision managePosition(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f,
            PositionSnapshot snap, Params p
    ) {
        String side = snap.getSide();
        if (side == null) return hold(ctx, "TPR manage: unknown side");

        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);
        boolean isRunner = POSITION_ROLE_RUNNER.equalsIgnoreCase(snap.getPositionRole());

        ManageState ms = buildManageState(snap, md, isLong);
        if (ms == null) return hold(ctx, "TPR manage: invalid init risk");
        if (ms.move().compareTo(ZERO) <= 0) return hold(ctx, "TPR manage: not in profit");

        return isRunner
                ? manageRunnerLeg(ctx, f, snap, p, side, isLong, ms)
                : manageTp1Leg(ctx, snap, p, side, isLong, ms);
    }

    /** Per-tick state used by both TP1 BE and runner-trail branches. */
    private record ManageState(BigDecimal entry, BigDecimal curStop, BigDecimal initRisk,
                               BigDecimal close, BigDecimal move, BigDecimal rMultiple) {}

    /** Compute the per-tick state, or null when initRisk is non-positive
     *  (which means the stored stop is on the wrong side of entry — a
     *  degenerate row, refuse to manage). */
    private ManageState buildManageState(PositionSnapshot snap, MarketData md, boolean isLong) {
        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal initStop = snap.getInitialStopLossPrice() != null
                ? snap.getInitialStopLossPrice() : curStop;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal initRisk = isLong ? entry.subtract(initStop) : initStop.subtract(entry);
        if (initRisk.compareTo(ZERO) <= 0) return null;
        BigDecimal move = isLong ? close.subtract(entry) : entry.subtract(close);
        BigDecimal rMultiple = move.compareTo(ZERO) > 0
                ? move.divide(initRisk, 8, RoundingMode.HALF_UP)
                : ZERO;
        return new ManageState(entry, curStop, initRisk, close, move, rMultiple);
    }

    /** TP1 leg: move stop to break-even once trade is in profit enough. */
    private StrategyDecision manageTp1Leg(EnrichedStrategyContext ctx, PositionSnapshot snap,
                                          Params p, String side, boolean isLong, ManageState ms) {
        BigDecimal beTrigger = ms.initRisk().multiply(p.getBreakEvenR());
        if (ms.move().compareTo(beTrigger) < 0) return hold(ctx, "TPR BE not ready");

        boolean alreadyAtBe = isLong
                ? ms.curStop().compareTo(ms.entry()) >= 0
                : ms.curStop().compareTo(ms.entry()) <= 0;
        if (alreadyAtBe) return hold(ctx, "TPR stop already at BE");

        return buildManagementDecision(
                ctx,
                new ManageAttribution(side, isLong ? SETUP_LONG_BE : SETUP_SHORT_BE, POSITION_ROLE_TP1),
                ms.entry(), snap.getTakeProfitPrice(),
                "Move TP1 leg to break-even at " + p.getBreakEvenR() + "R",
                Map.of(DIAG_ENTRY, ms.entry(), "curStop", ms.curStop(),
                        "close", ms.close(), "rMultiple", ms.rMultiple())
        );
    }

    /** Runner leg: phase-based ATR trail. */
    private StrategyDecision manageRunnerLeg(EnrichedStrategyContext ctx, FeatureStore f,
                                             PositionSnapshot snap, Params p,
                                             String side, boolean isLong, ManageState ms) {
        BigDecimal atr = resolveAtr(f);
        BigDecimal candidate = computeRunnerCandidate(isLong, ms, atr, p);
        if (candidate == null) return hold(ctx, "TPR runner not ready");

        // Stop can only move in the favourable direction.
        boolean improved = isLong
                ? candidate.compareTo(ms.curStop()) > 0
                : candidate.compareTo(ms.curStop()) < 0;
        if (!improved) return hold(ctx, "TPR runner stop already optimal");

        return buildTrailDecision(
                ctx,
                new ManageAttribution(side, isLong ? SETUP_LONG_TRAIL : SETUP_SHORT_TRAIL, POSITION_ROLE_RUNNER),
                candidate, snap.getTakeProfitPrice(),
                "TPR runner trail at " + ms.rMultiple() + "R",
                Map.of("rMultiple", ms.rMultiple(), "candidate", candidate,
                        "curStop", ms.curStop(), "close", ms.close(), "atr", atr)
        );
    }

    /** Phase-3 / Phase-2 / runner-BE candidate stop, or null when none of
     *  the phases applies yet. Each phase mixes an ATR trail with a locked
     *  R-multiple minimum so the runner can't give back too much. */
    private BigDecimal computeRunnerCandidate(boolean isLong, ManageState ms, BigDecimal atr, Params p) {
        BigDecimal r = ms.rMultiple();
        if (r.compareTo(p.getRunnerPhase3R()) >= 0) {
            return phaseTrailStop(isLong, ms, atr, p.getRunnerAtrPhase3(), p.getRunnerLockPhase3R());
        }
        if (r.compareTo(p.getRunnerPhase2R()) >= 0) {
            return phaseTrailStop(isLong, ms, atr, p.getRunnerAtrPhase2(), p.getRunnerLockPhase2R());
        }
        if (r.compareTo(p.getRunnerBreakEvenR()) >= 0) {
            boolean alreadyAtBe = isLong
                    ? ms.curStop().compareTo(ms.entry()) >= 0
                    : ms.curStop().compareTo(ms.entry()) <= 0;
            return alreadyAtBe ? null : ms.entry();
        }
        return null;
    }

    /** ATR trail stop for a given phase, clamped to a minimum locked
     *  R-multiple from entry so giveback is bounded. */
    private static BigDecimal phaseTrailStop(boolean isLong, ManageState ms, BigDecimal atr,
                                             BigDecimal atrMult, BigDecimal lockR) {
        BigDecimal atrTrail = isLong
                ? ms.close().subtract(atr.multiply(atrMult))
                : ms.close().add(atr.multiply(atrMult));
        BigDecimal lockStop = isLong
                ? ms.entry().add(ms.initRisk().multiply(lockR))
                : ms.entry().subtract(ms.initRisk().multiply(lockR));
        return isLong ? atrTrail.max(lockStop) : atrTrail.min(lockStop);
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private BigDecimal calculateLongSignalScore(FeatureStore f, Params p) {
        // Deliberately simple sum of normalised components; tune in v0.2.
        BigDecimal rsiScore = f.getRsi() != null
                ? normalise(f.getRsi(), p.getLongRsiMin(), p.getLongRsiMax())
                : ZERO;
        BigDecimal clvScore = f.getCloseLocationValue() != null ? f.getCloseLocationValue() : ZERO;
        BigDecimal bodyScore = f.getBodyToRangeRatio() != null ? f.getBodyToRangeRatio() : ZERO;
        BigDecimal volScore = f.getRelativeVolume20() != null
                ? f.getRelativeVolume20().divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP).min(ONE)
                : ZERO;

        // Weighted sum; capped at 1.
        BigDecimal score = rsiScore.multiply(new BigDecimal("0.25"))
                .add(clvScore.multiply(new BigDecimal("0.30")))
                .add(bodyScore.multiply(new BigDecimal("0.20")))
                .add(volScore.multiply(new BigDecimal("0.25")));
        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortSignalScore(FeatureStore f, Params p) {
        BigDecimal rsiScore = f.getRsi() != null
                ? normalise(p.getShortRsiMax().subtract(f.getRsi()),
                        ZERO, p.getShortRsiMax().subtract(p.getShortRsiMin()))
                : ZERO;
        BigDecimal clvScore = f.getCloseLocationValue() != null
                ? ONE.subtract(f.getCloseLocationValue()) : ZERO;
        BigDecimal bodyScore = f.getBodyToRangeRatio() != null ? f.getBodyToRangeRatio() : ZERO;
        BigDecimal volScore = f.getRelativeVolume20() != null
                ? f.getRelativeVolume20().divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP).min(ONE)
                : ZERO;

        BigDecimal score = rsiScore.multiply(new BigDecimal("0.25"))
                .add(clvScore.multiply(new BigDecimal("0.30")))
                .add(bodyScore.multiply(new BigDecimal("0.20")))
                .add(volScore.multiply(new BigDecimal("0.25")));
        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal normalise(BigDecimal value, BigDecimal lo, BigDecimal hi) {
        if (value == null || lo == null || hi == null) return ZERO;
        BigDecimal range = hi.subtract(lo);
        if (range.compareTo(ZERO) <= 0) return ZERO;
        BigDecimal scaled = value.subtract(lo).divide(range, 8, RoundingMode.HALF_UP);
        if (scaled.compareTo(ZERO) < 0) return ZERO;
        if (scaled.compareTo(ONE) > 0) return ONE;
        return scaled;
    }

    // ── Gating helpers ────────────────────────────────────────────────────────

    private boolean hasBullishEmaStack(MarketData md, @NonNull FeatureStore f, Params p) {
        if (f.getEma50() == null || f.getEma200() == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) <= 0) return false;
        if (f.getEma50().compareTo(f.getEma200()) <= 0) return false;
        return f.getEma50Slope() == null
                || f.getEma50Slope().compareTo(p.getEma50SlopeMin()) >= 0;
    }

    private boolean hasBearishEmaStack(MarketData md, @NonNull FeatureStore f, Params p) {
        if (f.getEma50() == null || f.getEma200() == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) >= 0) return false;
        if (f.getEma50().compareTo(f.getEma200()) >= 0) return false;
        return f.getEma50Slope() == null
                || f.getEma50Slope().negate().compareTo(p.getEma50SlopeMin()) >= 0;
    }

    private boolean isBullishBias(EnrichedStrategyContext ctx, Params p) {
        FeatureStore bias = ctx.getBiasFeatureStore();
        MarketData biasMd = ctx.getBiasMarketData();
        if (bias == null || biasMd == null) return false;

        boolean structure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasMd.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) > 0
                && biasMd.getClosePrice().compareTo(bias.getEma200()) > 0;
        if (!structure) return false;

        // v0.1 showed ADX 30–40 on the 4H bias was the only profitable bucket.
        // Below the min the trend isn't real; above the max it's over-extended
        // and the pullback is likely a top, not a retracement.
        return isWithinAdxBand(bias.getAdx(), p);
    }

    private boolean isBearishBias(EnrichedStrategyContext ctx, Params p) {
        FeatureStore bias = ctx.getBiasFeatureStore();
        MarketData biasMd = ctx.getBiasMarketData();
        if (bias == null || biasMd == null) return false;

        boolean structure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasMd.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) < 0
                && biasMd.getClosePrice().compareTo(bias.getEma200()) < 0;
        if (!structure) return false;

        return isWithinAdxBand(bias.getAdx(), p);
    }

    private boolean isWithinAdxBand(BigDecimal adx, Params p) {
        if (adx == null) return false;
        return adx.compareTo(p.getBiasAdxMin()) >= 0
                && adx.compareTo(p.getBiasAdxMax()) <= 0;
    }

    private boolean isMarketVetoed(EnrichedStrategyContext ctx) {
        return ctx.getMarketQualitySnapshot() != null
                && Boolean.FALSE.equals(ctx.getMarketQualitySnapshot().getTradable());
    }

    // ── Decision builders ─────────────────────────────────────────────────────

    /** Bundle of attribution + scope fields shared by every management /
     *  trail decision row. Pulled out to keep the decision-builder method
     *  signatures under Sonar's parameter ceiling. */
    private record ManageAttribution(String side, String setupType, String targetRole) {}

    private StrategyDecision buildManagementDecision(
            EnrichedStrategyContext ctx, ManageAttribution attr,
            BigDecimal newStop, BigDecimal takeProfit, String reason,
            Map<String, Object> diag
    ) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(attr.setupType())
                .side(attr.side())
                .reason(reason)
                .stopLossPrice(newStop)
                .takeProfitPrice1(takeProfit)
                .targetPositionRole(attr.targetRole())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", STRATEGY_CODE, attr.side(), "BREAK_EVEN"))
                .diagnostics(diag)
                .build();
    }

    private StrategyDecision buildTrailDecision(
            EnrichedStrategyContext ctx, ManageAttribution attr,
            BigDecimal newStop, BigDecimal takeProfit, String reason,
            Map<String, Object> diag
    ) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(attr.setupType())
                .side(attr.side())
                .reason(reason)
                .stopLossPrice(newStop)
                .trailingStopPrice(newStop)
                .takeProfitPrice1(takeProfit)
                .targetPositionRole(attr.targetRole())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", STRATEGY_CODE, attr.side(), "TRAIL"))
                .diagnostics(diag)
                .build();
    }

    private StrategyDecision hold(EnrichedStrategyContext ctx, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx != null ? ctx.getInterval() : null)
                .signalType(SIGNAL_TYPE_RECLAIM)
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", STRATEGY_CODE))
                .build();
    }

    private StrategyDecision veto(String vetoReason, EnrichedStrategyContext ctx) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx != null ? ctx.getInterval() : null)
                .vetoed(Boolean.TRUE)
                .vetoReason(vetoReason)
                .reason("TPR vetoed by risk layer")
                .jumpRiskScore(ctx != null ? resolveJumpRisk(ctx) : ZERO)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", STRATEGY_CODE, "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }

    // ── Snapshot resolvers ────────────────────────────────────────────────────

    private BigDecimal resolveAtr(FeatureStore f) {
        return (f != null && f.getAtr() != null && f.getAtr().compareTo(ZERO) > 0) ? f.getAtr() : ONE;
    }

    private BigDecimal resolveRegimeScore(EnrichedStrategyContext ctx) {
        RegimeSnapshot r = ctx.getRegimeSnapshot();
        return (r != null && r.getTrendScore() != null) ? r.getTrendScore() : ZERO;
    }

    private BigDecimal resolveJumpRisk(EnrichedStrategyContext ctx) {
        VolatilitySnapshot v = ctx.getVolatilitySnapshot();
        return (v != null && v.getJumpRiskScore() != null) ? v.getJumpRiskScore() : ZERO;
    }

    private BigDecimal resolveRiskMultiplier(EnrichedStrategyContext ctx) {
        RiskSnapshot r = ctx.getRiskSnapshot();
        return (r != null && r.getRiskMultiplier() != null) ? r.getRiskMultiplier() : ONE;
    }

    private String resolveRegimeLabel(EnrichedStrategyContext ctx, FeatureStore f) {
        if (ctx.getRegimeSnapshot() != null && ctx.getRegimeSnapshot().getRegimeLabel() != null) {
            return ctx.getRegimeSnapshot().getRegimeLabel();
        }
        return f != null ? f.getTrendRegime() : null;
    }

    // ── Params ────────────────────────────────────────────────────────────────

    /**
     * All tunable knobs for TPR live here. {@link #defaults()} is the canonical
     * starting point for research — tweak the numbers, restart, re-run a
     * backtest. If/when TPR graduates to live, we'll add a DB-backed
     * {@code TprStrategyParamService} mirroring the LSR/VCB pattern.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Params {
        // ── Trend filter ──
        /** EMA50 slope must be at least this much to consider the trend rising / falling. */
        private BigDecimal ema50SlopeMin;

        // ── 4H bias ADX band (v0.2) ──
        /** Lower bound on the 4H bias ADX — below this the MTF trend isn't real. */
        private BigDecimal biasAdxMin;
        /** Upper bound on the 4H bias ADX — above this the trend is over-extended
         *  and a pullback is more likely a reversal than a retracement. */
        private BigDecimal biasAdxMax;

        // ── ADX + DI gates ──
        private BigDecimal adxEntryMin;
        private BigDecimal adxEntryMax;
        /** +DI − −DI (long) or −DI − +DI (short) must exceed this. */
        private BigDecimal diSpreadMin;

        // ── Pullback ──
        /** Bar must touch EMA20 within this many ATRs on the adverse side. */
        private BigDecimal pullbackTouchAtr;

        // ── RSI windows ──
        private BigDecimal longRsiMin;
        private BigDecimal longRsiMax;
        private BigDecimal shortRsiMin;
        private BigDecimal shortRsiMax;

        // ── Reclaim candle quality ──
        /** Body / total-range ratio floor. */
        private BigDecimal bodyRatioMin;
        /** Close-location-value floor (long) / ceiling mirrored for short. */
        private BigDecimal clvMin;
        /** Close-location-value ceiling (long) / floor mirrored for short.
         *  v0.1 showed CLV >= 0.90 bars lost 7/7 — rejects exhaustion closes. */
        private BigDecimal clvMax;
        /** Relative volume floor vs 20-bar average. */
        private BigDecimal rvolMin;

        // ── Risk / exits ──
        /** ATRs of padding beyond the structural stop. */
        private BigDecimal stopAtrBuffer;
        /** Hard cap on per-trade risk as fraction of entry price. */
        private BigDecimal maxEntryRiskPct;
        /** TP1 target as R-multiple. */
        private BigDecimal tp1R;

        // ── Position sizing (V2 — risk-based) ──
        /** Fraction of cash balance to risk per trade (e.g. 0.02 = 2%).
         *  Position size is computed so the loss at stopLossPrice equals
         *  approximately riskPerTradePct × capital, then capped at
         *  {@link #maxAllocationPct} so the trade always executes.
         *  When null or non-positive, falls back to the legacy
         *  capital_allocation_pct flat sizing.
         *
         *  <p>Note: when this strategy graduates to live and is hydrated from
         *  the {@code strategy_param} JSONB column, rows persisted before the
         *  V2 sizing change won't have this key — the field will be null and
         *  the strategy reverts to legacy sizing. Re-publish the row (or
         *  add a Flyway data migration) to opt those accounts in. */
        private BigDecimal riskPerTradePct;
        /** Cap on position notional as fraction of cash balance.
         *  <b>Effective range is [0, 1.0]:</b> values above 1.0 are silently
         *  clamped at 1.0 by {@link StrategyHelper#calculateRiskBasedNotional}
         *  because the current backtest engine is cash-1× — leverage isn't
         *  modelled, so >1.0 would fail the balance check rather than
         *  amplifying the position. Lift the clamp once futures-leverage
         *  backtest is implemented. When null, the AccountStrategy's
         *  capital_allocation_pct (÷100) is used. Only consulted when
         *  {@link #riskPerTradePct} is set. */
        private BigDecimal maxAllocationPct;

        // ── Position management ──
        /** R-multiple at which the TP1 leg moves to break-even. */
        private BigDecimal breakEvenR;
        /** Runner: R-multiples gating each trail phase. */
        private BigDecimal runnerBreakEvenR;
        private BigDecimal runnerPhase2R;
        private BigDecimal runnerPhase3R;
        /** Runner: ATR distances per phase. */
        private BigDecimal runnerAtrPhase2;
        private BigDecimal runnerAtrPhase3;
        /** Runner: minimum locked-in R per phase. */
        private BigDecimal runnerLockPhase2R;
        private BigDecimal runnerLockPhase3R;

        // ── Score ──
        private BigDecimal minSignalScore;

        public static Params defaults() {
            return Params.builder()
                    // Trend
                    .ema50SlopeMin(new BigDecimal("0"))
                    // 4H bias ADX band (v0.2b): only profitable bucket observed was 30–40.
                    // Widened by 5 points either side so we don't starve for signals while
                    // still rejecting the two worst cohorts (<25, >40).
                    .biasAdxMin(new BigDecimal("25"))
                    .biasAdxMax(new BigDecimal("40"))
                    // ADX / DI — v0.2b: raised floor (ADX <25 was a coinflip in v0.1).
                    // Reverted diSpreadMin to v0.1 value — the 4.0 bump was speculative
                    // and compounded with other filters produced 0 trades.
                    .adxEntryMin(new BigDecimal("25"))
                    .adxEntryMax(new BigDecimal("45"))
                    .diSpreadMin(new BigDecimal("2.0"))
                    // Pullback
                    .pullbackTouchAtr(new BigDecimal("0.40"))
                    // RSI windows — reverted to v0.1. Tightening longRsiMax to 55 in v0.2
                    // killed 80% of signals because the 55–60 bucket contained most of the
                    // v0.1 trades (and was slightly better WR than 50–55). The "chasing"
                    // hypothesis wasn't supported by data.
                    .longRsiMin(new BigDecimal("38"))
                    .longRsiMax(new BigDecimal("60"))
                    .shortRsiMin(new BigDecimal("40"))
                    .shortRsiMax(new BigDecimal("62"))
                    // Reclaim candle — v0.3: raised clvMin 0.60 → 0.70. Across v0.1+v0.2b
                    // the 0.60–0.70 CLV bucket lost 0/11 trades — cleanest filter signal
                    // in the sample. The 0.90 ceiling (v0.2b) and rvol floor stay.
                    .bodyRatioMin(new BigDecimal("0.45"))
                    .clvMin(new BigDecimal("0.70"))
                    .clvMax(new BigDecimal("0.90"))
                    .rvolMin(new BigDecimal("1.10"))
                    // Risk — v0.2b: wider stop buffer retained. Losers in v0.1 got tagged
                    // at MAE-R median 1.12, right at the noise floor.
                    .stopAtrBuffer(new BigDecimal("0.55"))
                    .maxEntryRiskPct(new BigDecimal("0.04"))
                    .tp1R(new BigDecimal("2.00"))
                    // Sizing — v0.5: risk-based notional. Position is sized so
                    // hitting the stop = ~2% of capital, capped at 100% of cash
                    // (no leverage) so the trade always clears the balance check.
                    // Researcher can sweep these to find the right risk profile.
                    .riskPerTradePct(new BigDecimal("0.02"))
                    .maxAllocationPct(new BigDecimal("1.00"))
                    // Management — v0.4: loosened runner trail. v0.3 winners hit MFE-R of
                    // 2.04 and 3.42 but only realized 0.92 and 1.61 — the 1.80/1.20 ATR
                    // trail was catching normal BTC-1h breathing before moves extended.
                    // Widening phase-2 to 2.50 × ATR and phase-3 to 1.80 × ATR gives the
                    // runner more room; lock-in R multiples are unchanged so downside is
                    // capped the same way.
                    .breakEvenR(new BigDecimal("1.00"))
                    .runnerBreakEvenR(new BigDecimal("1.00"))
                    .runnerPhase2R(new BigDecimal("2.00"))
                    .runnerPhase3R(new BigDecimal("3.50"))
                    .runnerAtrPhase2(new BigDecimal("2.50"))
                    .runnerAtrPhase3(new BigDecimal("1.80"))
                    .runnerLockPhase2R(new BigDecimal("1.00"))
                    .runnerLockPhase3R(new BigDecimal("2.50"))
                    // Score — reverted to v0.1. Score isn't predictive but lowering the
                    // threshold without redesigning the formula doesn't help.
                    .minSignalScore(new BigDecimal("0.55"))
                    .build();
        }
    }
}
