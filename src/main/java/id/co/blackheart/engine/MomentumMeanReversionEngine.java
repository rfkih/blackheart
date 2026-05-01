package id.co.blackheart.engine;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.RiskSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.strategy.VolatilitySnapshot;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.service.strategy.StrategyHelper;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Momentum mean-reversion archetype — fade an EMA-anchored extreme back to
 * a closer EMA target.
 *
 * <p>Long entry: close is more than {@code extremeAtrMult * ATR} below the
 * anchor EMA AND RSI below {@code rsiOversoldMax}. TP at the target EMA
 * (mean-reversion target). Symmetric short. Stop = close ∓ {@code stopAtrBuffer
 * * ATR}, BE shift at {@code breakEvenR}, force-exit at {@code maxBarsHeld}.
 *
 * <p>Anchor and target EMA are selected via spec body strings
 * {@code body.signals.anchorEma} (default {@code ema200}) and
 * {@code body.signals.targetEma} (default {@code ema50}).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MomentumMeanReversionEngine implements StrategyEngine {

    public static final String ARCHETYPE = "momentum_mean_reversion";
    public static final int VERSION = 1;

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String DEFAULT_SIGNAL_TYPE_ENTRY      = "MMR_FADE";
    private static final String SIGNAL_TYPE_MANAGEMENT          = "POSITION_MANAGEMENT";

    private static final String DEFAULT_SETUP_LONG_ENTRY        = "MMR_LONG_FADE";
    private static final String DEFAULT_SETUP_SHORT_ENTRY       = "MMR_SHORT_FADE";
    private static final String DEFAULT_SETUP_LONG_TIMED_EXIT   = "MMR_LONG_TIMED_EXIT";
    private static final String DEFAULT_SETUP_SHORT_TIMED_EXIT  = "MMR_SHORT_TIMED_EXIT";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String TARGET_ALL = "ALL";

    private static final String DEFAULT_ANCHOR_EMA = "ema200";
    private static final String DEFAULT_TARGET_EMA = "ema50";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;

    private static final BigDecimal DEFAULT_EXTREME_ATR_MULT  = new BigDecimal("2.0");
    private static final BigDecimal DEFAULT_RSI_OVERSOLD_MAX  = new BigDecimal("30");
    private static final BigDecimal DEFAULT_RSI_OVERBOUGHT_MIN = new BigDecimal("70");
    private static final BigDecimal DEFAULT_STOP_ATR_BUFFER   = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_MIN_RR            = new BigDecimal("1.5");
    private static final BigDecimal DEFAULT_BREAK_EVEN_R      = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_MAX_ENTRY_RISK_PCT = new BigDecimal("0.04");
    private static final int        DEFAULT_MAX_BARS_HELD      = 12;
    private static final int        DEFAULT_INTERVAL_MINUTES   = 60;
    private static final boolean    DEFAULT_USE_RISK_SIZING    = true;
    private static final BigDecimal DEFAULT_RISK_PCT           = new BigDecimal("0.02");

    private final StrategyHelper strategyHelper;

    @Override
    public String archetype() {
        return ARCHETYPE;
    }

    @Override
    public int supportedVersion() {
        return VERSION;
    }

    @Override
    public StrategyRequirements requirements(StrategySpec spec) {
        return StrategyRequirements.builder()
                .requireBiasTimeframe(false)
                .requireRegimeSnapshot(true)
                .requireVolatilitySnapshot(true)
                .requireRiskSnapshot(true)
                .requireMarketQualitySnapshot(true)
                .requirePreviousFeatureStore(false)
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

        if (isMarketVetoed(context)) return veto(spec, context, "Market vetoed");

        if (context.hasTradablePosition() && snap != null) {
            return managePosition(spec, context, md, snap, t);
        }

        if (context.isLongAllowed()) {
            StrategyDecision d = tryLongEntry(spec, context, md, f, t);
            if (d != null) return d;
        }
        if (context.isShortAllowed()) {
            StrategyDecision d = tryShortEntry(spec, context, md, f, t);
            if (d != null) return d;
        }
        return hold(spec, context, "No qualified MMR setup");
    }

    private StrategyDecision tryLongEntry(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                          FeatureStore f, Tuning t) {
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal anchor = readEma(f, t.anchorEma);
        BigDecimal target = readEma(f, t.targetEma);
        if (anchor == null || target == null) return null;

        BigDecimal atr = resolveAtr(f);
        if (atr == null) return null;
        BigDecimal extremeDistance = atr.multiply(t.extremeAtrMult);

        if (close.compareTo(anchor.subtract(extremeDistance)) >= 0) return null;
        if (f.getRsi() == null || f.getRsi().compareTo(t.rsiOversoldMax) > 0) return null;
        if (target.compareTo(close) <= 0) return null;

        BigDecimal entry = close;
        BigDecimal stop = close.subtract(atr.multiply(t.stopAtrBuffer));
        BigDecimal tp1 = target;
        BigDecimal riskPerUnit = entry.subtract(stop);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal rewardPerUnit = tp1.subtract(entry);
        if (rewardPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal rr = rewardPerUnit.divide(riskPerUnit, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(t.minRewardRiskRatio) < 0) return null;

        BigDecimal maxAllowedRisk = entry.multiply(t.maxEntryRiskPct);
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) return null;

        BigDecimal notional = resolveSize(ctx, SIDE_LONG, entry, stop, t);
        if (notional.compareTo(ZERO) <= 0) return hold(spec, ctx, "MMR long notional zero");

        log.info("MMR[{}] LONG ENTRY | time={} close={} anchor={} target={} rsi={} stop={} tp1={} rr={}",
                spec.getStrategyCode(), md.getEndTime(), entry, anchor, target,
                f.getRsi(), stop, tp1, rr);

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.OPEN_LONG)
                .signalType(signalEntry(spec)).setupType(setupLongEntry(spec)).side(SIDE_LONG)
                .reason("MMR long: close below anchor by " + t.extremeAtrMult + "·ATR + RSI oversold")
                .signalScore(ONE).confidenceScore(ONE)
                .regimeScore(resolveRegimeScore(ctx)).riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .notionalSize(notional).stopLossPrice(stop).takeProfitPrice1(tp1)
                .exitStructure(EXIT_STRUCTURE_SINGLE).targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx()).entryAtr(f.getAtr()).entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", spec.getStrategyCode(), "LONG", ARCHETYPE))
                .diagnostics(Map.of("entry", entry, "stop", stop, "tp1", tp1, "rr", rr,
                        "anchor", anchor, "target", target, "atr", atr,
                        "rsi", strategyHelper.safe(f.getRsi())))
                .build();
    }

    private StrategyDecision tryShortEntry(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                           FeatureStore f, Tuning t) {
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal anchor = readEma(f, t.anchorEma);
        BigDecimal target = readEma(f, t.targetEma);
        if (anchor == null || target == null) return null;

        BigDecimal atr = resolveAtr(f);
        if (atr == null) return null;
        BigDecimal extremeDistance = atr.multiply(t.extremeAtrMult);

        if (close.compareTo(anchor.add(extremeDistance)) <= 0) return null;
        if (f.getRsi() == null || f.getRsi().compareTo(t.rsiOverboughtMin) < 0) return null;
        if (target.compareTo(close) >= 0) return null;

        BigDecimal entry = close;
        BigDecimal stop = close.add(atr.multiply(t.stopAtrBuffer));
        BigDecimal tp1 = target;
        BigDecimal riskPerUnit = stop.subtract(entry);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal rewardPerUnit = entry.subtract(tp1);
        if (rewardPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal rr = rewardPerUnit.divide(riskPerUnit, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(t.minRewardRiskRatio) < 0) return null;

        BigDecimal maxAllowedRisk = entry.multiply(t.maxEntryRiskPct);
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) return null;

        BigDecimal positionSize = resolveSize(ctx, SIDE_SHORT, entry, stop, t);
        if (positionSize.compareTo(ZERO) <= 0) return hold(spec, ctx, "MMR short position size zero");

        log.info("MMR[{}] SHORT ENTRY | time={} close={} anchor={} target={} rsi={} stop={} tp1={} rr={}",
                spec.getStrategyCode(), md.getEndTime(), entry, anchor, target,
                f.getRsi(), stop, tp1, rr);

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.OPEN_SHORT)
                .signalType(signalEntry(spec)).setupType(setupShortEntry(spec)).side(SIDE_SHORT)
                .reason("MMR short: close above anchor by " + t.extremeAtrMult + "·ATR + RSI overbought")
                .signalScore(ONE).confidenceScore(ONE)
                .regimeScore(resolveRegimeScore(ctx)).riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .positionSize(positionSize).stopLossPrice(stop).takeProfitPrice1(tp1)
                .exitStructure(EXIT_STRUCTURE_SINGLE).targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx()).entryAtr(f.getAtr()).entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", spec.getStrategyCode(), "SHORT", ARCHETYPE))
                .diagnostics(Map.of("entry", entry, "stop", stop, "tp1", tp1, "rr", rr,
                        "anchor", anchor, "target", target, "atr", atr,
                        "rsi", strategyHelper.safe(f.getRsi())))
                .build();
    }

    private StrategyDecision managePosition(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                            PositionSnapshot snap, Tuning t) {
        String side = snap.getSide();
        if (side == null) return hold(spec, ctx, "MMR manage: unknown side");
        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);

        LocalDateTime entryTime = snap.getEntryTime();
        LocalDateTime now = md.getEndTime();
        if (entryTime != null && now != null) {
            long minutesHeld = Duration.between(entryTime, now).toMinutes();
            long maxMinutes = (long) t.maxBarsHeld * t.intervalMinutes;
            if (minutesHeld >= maxMinutes) {
                log.info("MMR[{}] {} TIMED EXIT | minutesHeld={}", spec.getStrategyCode(), side, minutesHeld);
                return baseBuilder(spec, ctx)
                        .decisionType(isLong ? DecisionType.CLOSE_LONG : DecisionType.CLOSE_SHORT)
                        .signalType(SIGNAL_TYPE_MANAGEMENT)
                        .setupType(isLong ? setupLongTimedExit(spec) : setupShortTimedExit(spec)).side(side)
                        .reason("MMR maxBarsHeld reached")
                        .targetPositionRole(TARGET_ALL).decisionTime(LocalDateTime.now())
                        .tags(List.of("EXIT", spec.getStrategyCode(), side, "TIMED"))
                        .diagnostics(Map.of("close", strategyHelper.safe(md.getClosePrice())))
                        .build();
            }
        }

        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal initStop = snap.getInitialStopLossPrice() != null
                ? snap.getInitialStopLossPrice() : strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal initRisk = isLong ? entry.subtract(initStop) : initStop.subtract(entry);
        if (initRisk.compareTo(ZERO) <= 0) return hold(spec, ctx, "MMR manage: invalid init risk");
        BigDecimal move = isLong ? close.subtract(entry) : entry.subtract(close);
        BigDecimal rMultiple = move.divide(initRisk, 8, RoundingMode.HALF_UP);

        boolean alreadyAtBe = isLong ? curStop.compareTo(entry) >= 0 : curStop.compareTo(entry) <= 0;
        if (rMultiple.compareTo(t.breakEvenR) >= 0 && !alreadyAtBe) {
            log.info("MMR[{}] {} BE shift | rMultiple={} curStop={} -> entry={}",
                    spec.getStrategyCode(), side, rMultiple, curStop, entry);
            return baseBuilder(spec, ctx)
                    .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                    .signalType(SIGNAL_TYPE_MANAGEMENT).side(side)
                    .stopLossPrice(entry).trailingStopPrice(entry)
                    .takeProfitPrice1(snap.getTakeProfitPrice())
                    .targetPositionRole(TARGET_ALL)
                    .reason("MMR break-even shift at " + rMultiple + "R")
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("MANAGEMENT", spec.getStrategyCode(), side, "BREAK_EVEN"))
                    .diagnostics(Map.of("rMultiple", rMultiple, "curStop", curStop, "entry", entry))
                    .build();
        }
        return hold(spec, ctx, "MMR holding — SL/TP active");
    }

    private BigDecimal resolveSize(EnrichedStrategyContext ctx, String side,
                                   BigDecimal entry, BigDecimal stop, Tuning t) {
        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);
        BigDecimal allocation = isLong
                ? strategyHelper.calculateEntryNotional(ctx, SIDE_LONG)
                : strategyHelper.calculateShortPositionSize(ctx);
        if (!t.useRiskBasedSizing) return allocation;
        BigDecimal cash = ctx.getCashBalance();
        if (cash == null || cash.compareTo(ZERO) <= 0) return allocation;
        BigDecimal riskPerUnit = isLong ? entry.subtract(stop) : stop.subtract(entry);
        if (riskPerUnit.compareTo(ZERO) <= 0) return allocation;
        BigDecimal riskAmount = cash.multiply(t.riskPct);
        if (riskAmount.compareTo(ZERO) <= 0) return allocation;
        BigDecimal qty = riskAmount.divide(riskPerUnit, 12, RoundingMode.DOWN);
        BigDecimal candidate = isLong ? qty.multiply(entry).setScale(8, RoundingMode.HALF_UP)
                                      : qty.setScale(8, RoundingMode.HALF_UP);
        if (allocation.compareTo(ZERO) > 0 && candidate.compareTo(allocation) > 0) return allocation;
        return candidate;
    }

    /**
     * Resolve an EMA column from a spec-supplied name. Supports the three
     * indicator EMAs the FeatureStore exposes today; unknown names return
     * null which the caller treats as "feature missing → no entry".
     */
    private static BigDecimal readEma(FeatureStore f, String name) {
        if (f == null || name == null) return null;
        return switch (name.toLowerCase()) {
            case "ema20"  -> f.getEma20();
            case "ema50"  -> f.getEma50();
            case "ema200" -> f.getEma200();
            default -> null;
        };
    }

    private boolean isMarketVetoed(EnrichedStrategyContext ctx) {
        return ctx.getMarketQualitySnapshot() != null
                && Boolean.FALSE.equals(ctx.getMarketQualitySnapshot().getTradable());
    }

    private BigDecimal resolveAtr(FeatureStore f) {
        if (f == null || f.getAtr() == null) return null;
        return f.getAtr().compareTo(ZERO) > 0 ? f.getAtr() : null;
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

    private StrategyDecision.StrategyDecisionBuilder baseBuilder(StrategySpec spec, EnrichedStrategyContext ctx) {
        String name = spec.getStrategyName();
        if (name == null || name.isBlank()) name = spec.getStrategyCode();
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
                .signalType(signalEntry(spec)).reason(reason).decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", spec.getStrategyCode(), ARCHETYPE)).build();
    }

    private StrategyDecision veto(StrategySpec spec, EnrichedStrategyContext ctx, String reason) {
        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.HOLD)
                .vetoed(Boolean.TRUE).vetoReason(reason).reason("MMR vetoed").decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", spec.getStrategyCode(), ARCHETYPE, "RISK_LAYER")).diagnostics(Map.of()).build();
    }

    private String signalEntry(StrategySpec spec) {
        return spec.bodyString("signals.entrySignalType", DEFAULT_SIGNAL_TYPE_ENTRY);
    }
    private String setupLongEntry(StrategySpec spec) {
        return spec.bodyString("signals.setupLongEntry", DEFAULT_SETUP_LONG_ENTRY);
    }
    private String setupShortEntry(StrategySpec spec) {
        return spec.bodyString("signals.setupShortEntry", DEFAULT_SETUP_SHORT_ENTRY);
    }
    private String setupLongTimedExit(StrategySpec spec) {
        return spec.bodyString("signals.setupLongTimedExit", DEFAULT_SETUP_LONG_TIMED_EXIT);
    }
    private String setupShortTimedExit(StrategySpec spec) {
        return spec.bodyString("signals.setupShortTimedExit", DEFAULT_SETUP_SHORT_TIMED_EXIT);
    }

    private static final class Tuning {
        final BigDecimal extremeAtrMult;
        final BigDecimal rsiOversoldMax;
        final BigDecimal rsiOverboughtMin;
        final BigDecimal stopAtrBuffer;
        final BigDecimal minRewardRiskRatio;
        final BigDecimal breakEvenR;
        final BigDecimal maxEntryRiskPct;
        final int maxBarsHeld;
        final int intervalMinutes;
        final boolean useRiskBasedSizing;
        final BigDecimal riskPct;
        final String anchorEma;
        final String targetEma;

        private Tuning(StrategySpec s) {
            this.extremeAtrMult     = s.paramBigDecimal("extremeAtrMult", DEFAULT_EXTREME_ATR_MULT);
            this.rsiOversoldMax     = s.paramBigDecimal("rsiOversoldMax", DEFAULT_RSI_OVERSOLD_MAX);
            this.rsiOverboughtMin   = s.paramBigDecimal("rsiOverboughtMin", DEFAULT_RSI_OVERBOUGHT_MIN);
            this.stopAtrBuffer      = s.paramBigDecimal("stopAtrBuffer", DEFAULT_STOP_ATR_BUFFER);
            this.minRewardRiskRatio = s.paramBigDecimal("minRewardRiskRatio", DEFAULT_MIN_RR);
            this.breakEvenR         = s.paramBigDecimal("breakEvenR", DEFAULT_BREAK_EVEN_R);
            this.maxEntryRiskPct    = s.paramBigDecimal("maxEntryRiskPct", DEFAULT_MAX_ENTRY_RISK_PCT);
            this.maxBarsHeld        = s.paramInteger("maxBarsHeld", DEFAULT_MAX_BARS_HELD);
            this.intervalMinutes    = s.paramInteger("intervalMinutes", DEFAULT_INTERVAL_MINUTES);
            this.useRiskBasedSizing = s.paramBoolean("useRiskBasedSizing", DEFAULT_USE_RISK_SIZING);
            this.riskPct            = s.paramBigDecimal("riskPct", DEFAULT_RISK_PCT);
            this.anchorEma          = s.bodyString("signals.anchorEma", DEFAULT_ANCHOR_EMA);
            this.targetEma          = s.bodyString("signals.targetEma", DEFAULT_TARGET_EMA);
        }

        static Tuning from(StrategySpec spec) {
            return new Tuning(spec);
        }
    }
}
