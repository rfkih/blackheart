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
 * Mean-reversion oscillator archetype — generalised BBR.
 *
 * <p>Same gate triad as the hand-coded {@code BollingerReversalStrategyService}:
 * previous bar tagged the outer Bollinger Band, current bar reverses, and
 * RSI confirms exhaustion. Single exit at the BB middle (EMA20). Break-even
 * shift after {@code breakEvenR} favourable; force-exit at {@code maxBarsHeld}.
 *
 * <p>Tuning lives in {@link StrategySpec#getParams()} — the per-account
 * override layer in {@code strategy_param.param_overrides} merges on top
 * of archetype defaults at spec-resolution time, so this engine can read
 * a single Map.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MeanReversionOscillatorEngine implements StrategyEngine {

    public static final String ARCHETYPE = "mean_reversion_oscillator";
    public static final int VERSION = 1;

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    /** Defaults; specs may override via {@code body.signals.*} for parity with hand-coded ancestors. */
    private static final String DEFAULT_SIGNAL_TYPE_REVERSAL   = "MRO_BAND_EXHAUSTION";
    private static final String SIGNAL_TYPE_MANAGEMENT          = "POSITION_MANAGEMENT";

    private static final String DEFAULT_SETUP_LONG_FADE        = "MRO_LONG_LOWER_BAND_REVERSAL";
    private static final String DEFAULT_SETUP_SHORT_FADE       = "MRO_SHORT_UPPER_BAND_REVERSAL";
    private static final String DEFAULT_SETUP_LONG_TIMED_EXIT  = "MRO_LONG_TIMED_EXIT";
    private static final String DEFAULT_SETUP_SHORT_TIMED_EXIT = "MRO_SHORT_TIMED_EXIT";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String TARGET_ALL = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;

    private static final BigDecimal DEFAULT_RSI_OVERSOLD_MAX     = new BigDecimal("30");
    private static final BigDecimal DEFAULT_RSI_OVERBOUGHT_MIN   = new BigDecimal("70");
    private static final BigDecimal DEFAULT_STOP_ATR_BUFFER      = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_MIN_RR               = new BigDecimal("1.5");
    private static final BigDecimal DEFAULT_BREAK_EVEN_R         = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_MAX_ENTRY_RISK_PCT   = new BigDecimal("0.04");
    private static final int        DEFAULT_MAX_BARS_HELD        = 6;
    private static final int        DEFAULT_INTERVAL_MINUTES     = 60;
    private static final boolean    DEFAULT_USE_RISK_SIZING      = true;
    private static final BigDecimal DEFAULT_RISK_PCT             = new BigDecimal("0.02");

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
        FeatureStore prev = context.getPreviousFeatureStore();
        PositionSnapshot snap = context.getPositionSnapshot();

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ZERO) <= 0) return hold(spec, context, "Invalid close price");

        if (isMarketVetoed(context)) return veto(spec, context, "Market vetoed");

        if (context.hasTradablePosition() && snap != null) {
            return managePosition(spec, context, md, snap, t);
        }

        if (prev == null) return hold(spec, context, "No previous bar");

        if (context.isLongAllowed()) {
            StrategyDecision d = tryLongEntry(spec, context, md, f, prev, t);
            if (d != null) return d;
        }
        if (context.isShortAllowed()) {
            StrategyDecision d = tryShortEntry(spec, context, md, f, prev, t);
            if (d != null) return d;
        }
        return hold(spec, context, "No qualified MRO setup");
    }

    private StrategyDecision tryLongEntry(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                          FeatureStore f, FeatureStore prev, Tuning t) {
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal prevClose = strategyHelper.safe(prev.getPrice());

        if (prev.getBbLowerBand() == null || prev.getEma20() == null) return null;
        BigDecimal lowerBand = prev.getBbLowerBand();
        BigDecimal middle = prev.getEma20();
        if (prevClose.compareTo(lowerBand) > 0) return null;
        if (close.compareTo(prevClose) <= 0) return null;
        if (f.getRsi() == null || f.getRsi().compareTo(t.rsiOversoldMax) > 0) return null;

        BigDecimal atr = resolveAtr(f);
        if (atr == null) return null;
        BigDecimal entry = close;
        BigDecimal stop = lowerBand.subtract(atr.multiply(t.stopAtrBuffer));
        BigDecimal tp1 = middle;
        BigDecimal riskPerUnit = entry.subtract(stop);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal rewardPerUnit = tp1.subtract(entry);
        if (rewardPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal rr = rewardPerUnit.divide(riskPerUnit, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(t.minRewardRiskRatio) < 0) return null;

        BigDecimal maxAllowedRisk = entry.multiply(t.maxEntryRiskPct);
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) return null;

        BigDecimal notional = resolveSize(ctx, SIDE_LONG, entry, stop, t);
        if (notional.compareTo(ZERO) <= 0) return hold(spec, ctx, "MRO long notional zero");

        log.info("MRO[{}] LONG ENTRY | time={} close={} prevClose={} bbLower={} bbMid={} rsi={} stop={} tp1={} rr={}",
                spec.getStrategyCode(), md.getEndTime(), entry, prevClose, lowerBand, middle, f.getRsi(), stop, tp1, rr);

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.OPEN_LONG)
                .signalType(signalReversal(spec)).setupType(setupLongFade(spec)).side(SIDE_LONG)
                .reason("MRO long: lower BB touch + reversal candle + RSI oversold")
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
                        "bbLower", lowerBand, "bbMid", middle, "atr", atr,
                        "rsi", strategyHelper.safe(f.getRsi())))
                .build();
    }

    private StrategyDecision tryShortEntry(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                           FeatureStore f, FeatureStore prev, Tuning t) {
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal prevClose = strategyHelper.safe(prev.getPrice());

        if (prev.getBbUpperBand() == null || prev.getEma20() == null) return null;
        BigDecimal upperBand = prev.getBbUpperBand();
        BigDecimal middle = prev.getEma20();
        if (prevClose.compareTo(upperBand) < 0) return null;
        if (close.compareTo(prevClose) >= 0) return null;
        if (f.getRsi() == null || f.getRsi().compareTo(t.rsiOverboughtMin) < 0) return null;

        BigDecimal atr = resolveAtr(f);
        if (atr == null) return null;
        BigDecimal entry = close;
        BigDecimal stop = upperBand.add(atr.multiply(t.stopAtrBuffer));
        BigDecimal tp1 = middle;
        BigDecimal riskPerUnit = stop.subtract(entry);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal rewardPerUnit = entry.subtract(tp1);
        if (rewardPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal rr = rewardPerUnit.divide(riskPerUnit, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(t.minRewardRiskRatio) < 0) return null;

        BigDecimal maxAllowedRisk = entry.multiply(t.maxEntryRiskPct);
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) return null;

        BigDecimal positionSize = resolveSize(ctx, SIDE_SHORT, entry, stop, t);
        if (positionSize.compareTo(ZERO) <= 0) return hold(spec, ctx, "MRO short position size zero");

        log.info("MRO[{}] SHORT ENTRY | time={} close={} prevClose={} bbUpper={} bbMid={} rsi={} stop={} tp1={} rr={}",
                spec.getStrategyCode(), md.getEndTime(), entry, prevClose, upperBand, middle, f.getRsi(), stop, tp1, rr);

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.OPEN_SHORT)
                .signalType(signalReversal(spec)).setupType(setupShortFade(spec)).side(SIDE_SHORT)
                .reason("MRO short: upper BB touch + reversal candle + RSI overbought")
                .signalScore(ONE).confidenceScore(ONE)
                .regimeScore(resolveRegimeScore(ctx)).riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .positionSize(positionSize).stopLossPrice(stop).takeProfitPrice1(tp1)
                .exitStructure(EXIT_STRUCTURE_SINGLE).targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx()).entryAtr(f.getAtr()).entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", spec.getStrategyCode(), "SHORT", ARCHETYPE))
                .diagnostics(Map.of("entry", entry, "stop", stop, "tp1", tp1, "rr", rr))
                .build();
    }

    private StrategyDecision managePosition(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                            PositionSnapshot snap, Tuning t) {
        String side = snap.getSide();
        if (side == null) return hold(spec, ctx, "MRO manage: unknown side");
        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);

        LocalDateTime entryTime = snap.getEntryTime();
        LocalDateTime now = md.getEndTime();
        if (entryTime != null && now != null) {
            long minutesHeld = Duration.between(entryTime, now).toMinutes();
            long maxMinutes = (long) t.maxBarsHeld * t.intervalMinutes;
            if (minutesHeld >= maxMinutes) {
                log.info("MRO[{}] {} TIMED EXIT | minutesHeld={}", spec.getStrategyCode(), side, minutesHeld);
                return baseBuilder(spec, ctx)
                        .decisionType(isLong ? DecisionType.CLOSE_LONG : DecisionType.CLOSE_SHORT)
                        .signalType(SIGNAL_TYPE_MANAGEMENT)
                        .setupType(isLong ? setupLongTimedExit(spec) : setupShortTimedExit(spec)).side(side)
                        .reason("MRO maxBarsHeld reached")
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
        if (initRisk.compareTo(ZERO) <= 0) return hold(spec, ctx, "MRO manage: invalid init risk");
        BigDecimal move = isLong ? close.subtract(entry) : entry.subtract(close);
        BigDecimal rMultiple = move.divide(initRisk, 8, RoundingMode.HALF_UP);

        boolean alreadyAtBe = isLong ? curStop.compareTo(entry) >= 0 : curStop.compareTo(entry) <= 0;
        if (rMultiple.compareTo(t.breakEvenR) >= 0 && !alreadyAtBe) {
            log.info("MRO[{}] {} BE shift | rMultiple={} curStop={} -> entry={}",
                    spec.getStrategyCode(), side, rMultiple, curStop, entry);
            return baseBuilder(spec, ctx)
                    .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                    .signalType(SIGNAL_TYPE_MANAGEMENT).side(side)
                    .stopLossPrice(entry).trailingStopPrice(entry)
                    .takeProfitPrice1(snap.getTakeProfitPrice())
                    .targetPositionRole(TARGET_ALL)
                    .reason("MRO break-even shift at " + rMultiple + "R")
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("MANAGEMENT", spec.getStrategyCode(), side, "BREAK_EVEN"))
                    .diagnostics(Map.of("rMultiple", rMultiple, "curStop", curStop, "entry", entry))
                    .build();
        }
        return hold(spec, ctx, "MRO holding — SL/TP active");
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

    private boolean isMarketVetoed(EnrichedStrategyContext ctx) {
        return ctx.getMarketQualitySnapshot() != null
                && Boolean.FALSE.equals(ctx.getMarketQualitySnapshot().getTradable());
    }
    /**
     * Returns ATR if present and positive; null otherwise. Callers refuse the
     * entry on null — falling back to a fixed constant (the BBR ancestor used
     * {@code BigDecimal.ONE}) silently produces nonsense stop distances on
     * high-priced instruments and unbounded sizing.
     */
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
                .signalType(signalReversal(spec)).reason(reason).decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", spec.getStrategyCode(), ARCHETYPE)).build();
    }

    private StrategyDecision veto(StrategySpec spec, EnrichedStrategyContext ctx, String reason) {
        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.HOLD)
                .vetoed(Boolean.TRUE).vetoReason(reason).reason("MRO vetoed").decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", spec.getStrategyCode(), ARCHETYPE, "RISK_LAYER")).diagnostics(Map.of()).build();
    }

    // ── Spec-overridable label resolvers (body.signals.* takes precedence). ──
    private String signalReversal(StrategySpec spec) {
        return spec.bodyString("signals.reversalSignalType", DEFAULT_SIGNAL_TYPE_REVERSAL);
    }
    private String setupLongFade(StrategySpec spec) {
        return spec.bodyString("signals.setupLongFade", DEFAULT_SETUP_LONG_FADE);
    }
    private String setupShortFade(StrategySpec spec) {
        return spec.bodyString("signals.setupShortFade", DEFAULT_SETUP_SHORT_FADE);
    }
    private String setupLongTimedExit(StrategySpec spec) {
        return spec.bodyString("signals.setupLongTimedExit", DEFAULT_SETUP_LONG_TIMED_EXIT);
    }
    private String setupShortTimedExit(StrategySpec spec) {
        return spec.bodyString("signals.setupShortTimedExit", DEFAULT_SETUP_SHORT_TIMED_EXIT);
    }

    /** Snapshot of tuning values resolved from the spec. */
    private static final class Tuning {
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

        private Tuning(StrategySpec s) {
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
        }

        static Tuning from(StrategySpec spec) {
            return new Tuning(spec);
        }
    }
}
