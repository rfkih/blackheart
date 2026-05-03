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
 * Donchian breakout archetype — generalisation of the discarded DCT pattern
 * with the lessons baked in (rvol floor, ADX-rising filter optional via spec).
 *
 * <p>Long entry: current bar closes above the Donchian-N upper channel of the
 * previous bar (= max high of the 20 bars ending at prevBar, which does NOT
 * include the current bar); relative volume above floor; ADX above floor.
 * Symmetric short. Single TP at {@code tpR * initial_risk};
 * stop at {@code stopAtrMult * ATR} from entry. Break-even shift after
 * {@code breakEvenR}; force-exit at {@code maxBarsHeld}.
 *
 * <p>Tuning lives in {@link StrategySpec#getParams()}; per-account overrides
 * merge on top via the executor adapter at evaluate-time.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DonchianBreakoutEngine implements StrategyEngine {

    public static final String ARCHETYPE = "donchian_breakout";
    public static final int VERSION = 1;

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String DEFAULT_SIGNAL_TYPE_ENTRY      = "DCB_BREAKOUT";
    private static final String SIGNAL_TYPE_MANAGEMENT          = "POSITION_MANAGEMENT";

    private static final String DEFAULT_SETUP_LONG_ENTRY        = "DCB_LONG_BREAKOUT";
    private static final String DEFAULT_SETUP_SHORT_ENTRY       = "DCB_SHORT_BREAKOUT";
    private static final String DEFAULT_SETUP_LONG_TIMED_EXIT   = "DCB_LONG_TIMED_EXIT";
    private static final String DEFAULT_SETUP_SHORT_TIMED_EXIT  = "DCB_SHORT_TIMED_EXIT";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String TARGET_ALL = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;

    // Defaults align with the validated DCT tuning (rvolMin=1.30, ADX floor=20,
    // stopAtrMult=3.0, tpR=2.0). Mean-reversion lessons say SINGLE exit, not
    // RUNNER, so we keep the exit shape simple here too.
    private static final BigDecimal DEFAULT_RVOL_MIN          = new BigDecimal("1.30");
    private static final BigDecimal DEFAULT_ADX_ENTRY_MIN     = new BigDecimal("20");
    private static final BigDecimal DEFAULT_STOP_ATR_MULT     = new BigDecimal("3.0");
    private static final BigDecimal DEFAULT_TP_R              = new BigDecimal("2.0");
    private static final BigDecimal DEFAULT_BREAK_EVEN_R      = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_MAX_ENTRY_RISK_PCT = new BigDecimal("0.04");
    private static final int        DEFAULT_MAX_BARS_HELD      = 24;
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
        return hold(spec, context, "No qualified DCB setup");
    }

    private StrategyDecision tryLongEntry(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                          FeatureStore f, FeatureStore prev, Tuning t) {
        BigDecimal close = strategyHelper.safe(md.getClosePrice());

        if (prev.getDonchianUpper20() == null) return null;
        BigDecimal donchianUpper = prev.getDonchianUpper20();
        // prev.getDonchianUpper20() = max(high[t-1..t-20]) — does NOT include current bar.
        // close > donchianUpper implies close > prevBar.high >= prevClose (continuation implied).
        if (close.compareTo(donchianUpper) <= 0) return null;

        if (f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(t.rvolMin) < 0) return null;
        if (f.getAdx() == null || f.getAdx().compareTo(t.adxEntryMin) < 0) return null;

        BigDecimal atr = resolveAtr(f);
        if (atr == null) return null;
        BigDecimal entry = close;
        BigDecimal stop = entry.subtract(atr.multiply(t.stopAtrMult));
        BigDecimal riskPerUnit = entry.subtract(stop);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal tp1 = entry.add(riskPerUnit.multiply(t.tpR));

        BigDecimal maxAllowedRisk = entry.multiply(t.maxEntryRiskPct);
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) return null;

        BigDecimal notional = resolveSize(ctx, SIDE_LONG, entry, stop, t);
        if (notional.compareTo(ZERO) <= 0) return hold(spec, ctx, "DCB long notional zero");

        log.info("DCB[{}] LONG ENTRY | time={} close={} donchUpper={} rvol={} adx={} stop={} tp1={}",
                spec.getStrategyCode(), md.getEndTime(), entry, donchianUpper,
                f.getRelativeVolume20(), f.getAdx(), stop, tp1);

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.OPEN_LONG)
                .signalType(signalEntry(spec)).setupType(setupLongEntry(spec)).side(SIDE_LONG)
                .reason("DCB long: donchian breakout + continuation + volume + adx")
                .signalScore(ONE).confidenceScore(ONE)
                .regimeScore(resolveRegimeScore(ctx)).riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .notionalSize(notional).stopLossPrice(stop).takeProfitPrice1(tp1)
                .exitStructure(EXIT_STRUCTURE_SINGLE).targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx()).entryAtr(f.getAtr())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", spec.getStrategyCode(), "LONG", ARCHETYPE))
                .diagnostics(Map.of("entry", entry, "stop", stop, "tp1", tp1,
                        "donchUpper", donchianUpper, "atr", atr,
                        "rvol", strategyHelper.safe(f.getRelativeVolume20())))
                .build();
    }

    private StrategyDecision tryShortEntry(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                           FeatureStore f, FeatureStore prev, Tuning t) {
        BigDecimal close = strategyHelper.safe(md.getClosePrice());

        if (prev.getDonchianLower20() == null) return null;
        BigDecimal donchianLower = prev.getDonchianLower20();
        // prev.getDonchianLower20() = min(low[t-1..t-20]) — does NOT include current bar.
        // close < donchianLower implies close < prevBar.low <= prevClose (continuation implied).
        if (close.compareTo(donchianLower) >= 0) return null;

        if (f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(t.rvolMin) < 0) return null;
        if (f.getAdx() == null || f.getAdx().compareTo(t.adxEntryMin) < 0) return null;

        BigDecimal atr = resolveAtr(f);
        if (atr == null) return null;
        BigDecimal entry = close;
        BigDecimal stop = entry.add(atr.multiply(t.stopAtrMult));
        BigDecimal riskPerUnit = stop.subtract(entry);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;
        BigDecimal tp1 = entry.subtract(riskPerUnit.multiply(t.tpR));

        BigDecimal maxAllowedRisk = entry.multiply(t.maxEntryRiskPct);
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) return null;

        BigDecimal positionSize = resolveSize(ctx, SIDE_SHORT, entry, stop, t);
        if (positionSize.compareTo(ZERO) <= 0) return hold(spec, ctx, "DCB short position size zero");

        log.info("DCB[{}] SHORT ENTRY | time={} close={} donchLower={} rvol={} adx={} stop={} tp1={}",
                spec.getStrategyCode(), md.getEndTime(), entry, donchianLower,
                f.getRelativeVolume20(), f.getAdx(), stop, tp1);

        return baseBuilder(spec, ctx)
                .decisionType(DecisionType.OPEN_SHORT)
                .signalType(signalEntry(spec)).setupType(setupShortEntry(spec)).side(SIDE_SHORT)
                .reason("DCB short: donchian breakdown + continuation + volume + adx")
                .signalScore(ONE).confidenceScore(ONE)
                .regimeScore(resolveRegimeScore(ctx)).riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .positionSize(positionSize).stopLossPrice(stop).takeProfitPrice1(tp1)
                .exitStructure(EXIT_STRUCTURE_SINGLE).targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx()).entryAtr(f.getAtr())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", spec.getStrategyCode(), "SHORT", ARCHETYPE))
                .diagnostics(Map.of("entry", entry, "stop", stop, "tp1", tp1,
                        "donchLower", donchianLower, "atr", atr,
                        "rvol", strategyHelper.safe(f.getRelativeVolume20())))
                .build();
    }

    private StrategyDecision managePosition(StrategySpec spec, EnrichedStrategyContext ctx, MarketData md,
                                            PositionSnapshot snap, Tuning t) {
        String side = snap.getSide();
        if (side == null) return hold(spec, ctx, "DCB manage: unknown side");
        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);

        LocalDateTime entryTime = snap.getEntryTime();
        LocalDateTime now = md.getEndTime();
        if (entryTime != null && now != null) {
            long minutesHeld = Duration.between(entryTime, now).toMinutes();
            long maxMinutes = (long) t.maxBarsHeld * t.intervalMinutes;
            if (minutesHeld >= maxMinutes) {
                log.info("DCB[{}] {} TIMED EXIT | minutesHeld={}", spec.getStrategyCode(), side, minutesHeld);
                return baseBuilder(spec, ctx)
                        .decisionType(isLong ? DecisionType.CLOSE_LONG : DecisionType.CLOSE_SHORT)
                        .signalType(SIGNAL_TYPE_MANAGEMENT)
                        .setupType(isLong ? setupLongTimedExit(spec) : setupShortTimedExit(spec)).side(side)
                        .reason("DCB maxBarsHeld reached")
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
        if (initRisk.compareTo(ZERO) <= 0) return hold(spec, ctx, "DCB manage: invalid init risk");
        BigDecimal move = isLong ? close.subtract(entry) : entry.subtract(close);
        BigDecimal rMultiple = move.divide(initRisk, 8, RoundingMode.HALF_UP);

        boolean alreadyAtBe = isLong ? curStop.compareTo(entry) >= 0 : curStop.compareTo(entry) <= 0;
        if (rMultiple.compareTo(t.breakEvenR) >= 0 && !alreadyAtBe) {
            log.info("DCB[{}] {} BE shift | rMultiple={} curStop={} -> entry={}",
                    spec.getStrategyCode(), side, rMultiple, curStop, entry);
            return baseBuilder(spec, ctx)
                    .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                    .signalType(SIGNAL_TYPE_MANAGEMENT).side(side)
                    .stopLossPrice(entry).trailingStopPrice(entry)
                    .takeProfitPrice1(snap.getTakeProfitPrice())
                    .targetPositionRole(TARGET_ALL)
                    .reason("DCB break-even shift at " + rMultiple + "R")
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("MANAGEMENT", spec.getStrategyCode(), side, "BREAK_EVEN"))
                    .diagnostics(Map.of("rMultiple", rMultiple, "curStop", curStop, "entry", entry))
                    .build();
        }
        return hold(spec, ctx, "DCB holding — SL/TP active");
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
                .vetoed(Boolean.TRUE).vetoReason(reason).reason("DCB vetoed").decisionTime(LocalDateTime.now())
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
        final BigDecimal rvolMin;
        final BigDecimal adxEntryMin;
        final BigDecimal stopAtrMult;
        final BigDecimal tpR;
        final BigDecimal breakEvenR;
        final BigDecimal maxEntryRiskPct;
        final int maxBarsHeld;
        final int intervalMinutes;
        final boolean useRiskBasedSizing;
        final BigDecimal riskPct;

        private Tuning(StrategySpec s) {
            this.rvolMin            = s.paramBigDecimal("rvolMin", DEFAULT_RVOL_MIN);
            this.adxEntryMin        = s.paramBigDecimal("adxEntryMin", DEFAULT_ADX_ENTRY_MIN);
            this.stopAtrMult        = s.paramBigDecimal("stopAtrMult", DEFAULT_STOP_ATR_MULT);
            this.tpR                = s.paramBigDecimal("tpR", DEFAULT_TP_R);
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
