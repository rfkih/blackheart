package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.funding.FundingCarryParams;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
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

/**
 * Funding-Carry strategy ({@code FCARRY}).
 *
 * <p>Trades the funding-rate imbalance on perpetual futures: when one side is
 * paying an unusually high funding rate (z &gt; +entryZ), that side is
 * overcrowded — fade it. When the rate normalises (|z| &lt; exitZ) or the
 * holding window expires, exit. Stops sit at an ATR multiple from entry.
 *
 * <p>This is a Jane-Street/Citadel-style hand-written legacy strategy
 * (LEGACY_JAVA archetype) — params live in the unified
 * {@link StrategyParamService} JSONB store, defaults in
 * {@link FundingCarryParams}.
 *
 * <p>Wire-up: registered in {@link StrategyExecutorFactory} under code
 * {@code "FCARRY"}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FundingCarryStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;
    private final StrategyParamService strategyParamService;

    // ── Identity ──────────────────────────────────────────────────────────────
    private static final String STRATEGY_CODE    = "FCARRY";
    private static final String STRATEGY_NAME    = "Funding Carry";
    private static final String STRATEGY_VERSION = "v0_research";

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_CARRY      = "FUNDING_CARRY";
    private static final String SIGNAL_TYPE_MANAGEMENT = "POSITION_MANAGEMENT";

    private static final String SETUP_LONG_CARRY  = "FCARRY_LONG_FADE";
    private static final String SETUP_SHORT_CARRY = "FCARRY_SHORT_FADE";
    private static final String SETUP_EXIT_Z      = "FCARRY_EXIT_Z_NORMALISED";
    private static final String SETUP_EXIT_TIME   = "FCARRY_EXIT_TIME_STOP";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String TARGET_ALL = "ALL";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;

    // ── StrategyExecutor contract ─────────────────────────────────────────────

    @Override
    public StrategyRequirements getRequirements() {
        // No bias timeframe, no previous-FS bookkeeping — the carry signal is a
        // single-bar read of FeatureStore.fundingRateZ.
        return StrategyRequirements.builder()
                .requireBiasTimeframe(false)
                .requireRegimeSnapshot(false)
                .requireVolatilitySnapshot(false)
                .requireRiskSnapshot(false)
                .requireMarketQualitySnapshot(true)
                .requirePreviousFeatureStore(false)
                .build();
    }

    @Override
    public StrategyDecision execute(EnrichedStrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(context, "Invalid context or missing data");
        }

        UUID accountStrategyId = context.getAccountStrategy() != null
                ? context.getAccountStrategy().getAccountStrategyId() : null;
        FundingCarryParams p = FundingCarryParams.merge(
                strategyParamService.getActiveOverrides(accountStrategyId));

        MarketData md = context.getMarketData();
        FeatureStore f = context.getFeatureStore();
        PositionSnapshot snap = context.getPositionSnapshot();

        // Funding columns are NULL on spot symbols and during the cold-start
        // window before V35 backfill catches up — bail out without trading.
        if (f.getFundingRate8h() == null || f.getFundingRateZ() == null) {
            return hold(context, "Funding features unavailable for this bar");
        }

        if (isMarketVetoed(context)) {
            return veto("Market vetoed by quality filter", context);
        }

        // Already in a trade → exit if z normalised or time-stop.
        if (context.hasTradablePosition() && snap != null) {
            return managePosition(context, md, f, snap, p);
        }

        // Entry: SHORT when longs are paying premium (z > +entryZ, rate > 0).
        if (context.isShortAllowed() && Boolean.TRUE.equals(p.getAllowShort())) {
            StrategyDecision d = tryShortEntry(context, md, f, p);
            if (d != null) return d;
        }

        // Entry: LONG when shorts are paying premium (z < -entryZ, rate < 0).
        if (context.isLongAllowed() && Boolean.TRUE.equals(p.getAllowLong())) {
            StrategyDecision d = tryLongEntry(context, md, f, p);
            if (d != null) return d;
        }

        return hold(context, "No qualified FCARRY setup");
    }

    // ── Entries ───────────────────────────────────────────────────────────────

    private StrategyDecision tryLongEntry(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f, FundingCarryParams p
    ) {
        BigDecimal z = f.getFundingRateZ();
        BigDecimal rate8h = f.getFundingRate8h();
        BigDecimal entryThreshold = p.getEntryZ().negate();

        if (z.compareTo(entryThreshold) >= 0) return null;          // not extreme enough
        if (rate8h.signum() >= 0) return null;                      // shorts must be paying
        if (rate8h.abs().compareTo(p.getMinAbsRate8h()) < 0) return null;  // carry too small

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ZERO) <= 0) return null;

        // Optional trend filter — refuse fades that fight the prevailing trend.
        // A LONG carry trade goes with shorts paying premium; require close
        // not to be in a clean downtrend (close >= ema50) when the gate is on.
        if (Boolean.TRUE.equals(p.getRequireTrendAlignment())
                && f.getEma50() != null
                && close.compareTo(f.getEma50()) < 0) {
            return null;
        }

        BigDecimal atr = resolveAtr(f);
        BigDecimal stop = close.subtract(atr.multiply(p.getAtrStopMult()));
        if (stop.compareTo(ZERO) <= 0) return null;

        BigDecimal notional = strategyHelper.calculateEntryNotional(ctx, SIDE_LONG);
        if (notional.compareTo(ZERO) <= 0) return hold(ctx, "FCARRY long notional zero");

        log.info("FCARRY LONG ENTRY | time={} close={} z={} rate8h={} stop={}",
                md.getEndTime(), close, z, rate8h, stop);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_CARRY)
                .setupType(SETUP_LONG_CARRY)
                .side(SIDE_LONG)
                .reason("FCARRY long: shorts paying premium (z=" + z + ", rate8h=" + rate8h + ")")
                .signalScore(carryScore(z, p))
                .confidenceScore(carryScore(z, p))
                .notionalSize(notional)
                .stopLossPrice(stop)
                .exitStructure(EXIT_STRUCTURE_SINGLE)
                .targetPositionRole(TARGET_ALL)
                .maxHoldingBars(p.getHoldMaxBars())
                .entryAtr(f.getAtr())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", STRATEGY_CODE, SIDE_LONG, STRATEGY_VERSION))
                .diagnostics(Map.of(
                        "module", "FundingCarryStrategyService",
                        "fundingRateZ", z,
                        "fundingRate8h", rate8h,
                        "fundingRate7dAvg", strategyHelper.safe(f.getFundingRate7dAvg()),
                        "atr", atr,
                        "stop", stop))
                .build();
    }

    private StrategyDecision tryShortEntry(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f, FundingCarryParams p
    ) {
        BigDecimal z = f.getFundingRateZ();
        BigDecimal rate8h = f.getFundingRate8h();
        BigDecimal entryThreshold = p.getEntryZ();

        if (z.compareTo(entryThreshold) <= 0) return null;
        if (rate8h.signum() <= 0) return null;
        if (rate8h.abs().compareTo(p.getMinAbsRate8h()) < 0) return null;

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(ZERO) <= 0) return null;

        // Optional trend filter — refuse fades that fight the prevailing trend.
        // A SHORT carry trade goes with longs paying premium; require close
        // not to be in a clean uptrend (close <= ema50) when the gate is on.
        if (Boolean.TRUE.equals(p.getRequireTrendAlignment())
                && f.getEma50() != null
                && close.compareTo(f.getEma50()) > 0) {
            return null;
        }

        BigDecimal atr = resolveAtr(f);
        BigDecimal stop = close.add(atr.multiply(p.getAtrStopMult()));

        BigDecimal positionSize = strategyHelper.calculateShortPositionSize(ctx);
        if (positionSize.compareTo(ZERO) <= 0) return hold(ctx, "FCARRY short position size zero");

        log.info("FCARRY SHORT ENTRY | time={} close={} z={} rate8h={} stop={}",
                md.getEndTime(), close, z, rate8h, stop);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_CARRY)
                .setupType(SETUP_SHORT_CARRY)
                .side(SIDE_SHORT)
                .reason("FCARRY short: longs paying premium (z=" + z + ", rate8h=" + rate8h + ")")
                .signalScore(carryScore(z, p))
                .confidenceScore(carryScore(z, p))
                .positionSize(positionSize)
                .stopLossPrice(stop)
                .exitStructure(EXIT_STRUCTURE_SINGLE)
                .targetPositionRole(TARGET_ALL)
                .maxHoldingBars(p.getHoldMaxBars())
                .entryAtr(f.getAtr())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", STRATEGY_CODE, SIDE_SHORT, STRATEGY_VERSION))
                .diagnostics(Map.of(
                        "module", "FundingCarryStrategyService",
                        "fundingRateZ", z,
                        "fundingRate8h", rate8h,
                        "fundingRate7dAvg", strategyHelper.safe(f.getFundingRate7dAvg()),
                        "atr", atr,
                        "stop", stop))
                .build();
    }

    // ── Position management ───────────────────────────────────────────────────

    private StrategyDecision managePosition(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f,
            PositionSnapshot snap, FundingCarryParams p
    ) {
        String side = snap.getSide();
        if (side == null) return hold(ctx, "FCARRY manage: unknown side");
        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);

        // Carry-collapse exit — z reverted toward zero, edge is gone.
        BigDecimal z = f.getFundingRateZ();
        if (z != null && z.abs().compareTo(p.getExitZ()) < 0) {
            return closeDecision(ctx, side,
                    "FCARRY exit: |z|=" + z.abs() + " < exitZ=" + p.getExitZ(),
                    SETUP_EXIT_Z,
                    Map.of("fundingRateZ", z, "exitZ", p.getExitZ(), "trigger", "Z_NORMALISED"),
                    isLong);
        }

        // Time-stop — translate holdMaxBars into seconds via the strategy's
        // resolved interval and compare against entry-to-now duration.
        Long intervalSeconds = intervalSeconds(ctx.getInterval());
        if (intervalSeconds != null && snap.getEntryTime() != null && md.getEndTime() != null) {
            long heldSeconds = Duration.between(snap.getEntryTime(), md.getEndTime()).getSeconds();
            long maxSeconds = intervalSeconds * (long) p.getHoldMaxBars();
            if (heldSeconds >= maxSeconds) {
                return closeDecision(ctx, side,
                        "FCARRY exit: time-stop after " + p.getHoldMaxBars() + " bars",
                        SETUP_EXIT_TIME,
                        Map.of("heldSeconds", heldSeconds, "maxSeconds", maxSeconds,
                                "holdMaxBars", p.getHoldMaxBars(), "trigger", "TIME_STOP"),
                        isLong);
            }
        }

        return hold(ctx, "FCARRY hold");
    }

    private StrategyDecision closeDecision(
            EnrichedStrategyContext ctx, String side, String reason, String setupType,
            Map<String, Object> diag, boolean isLong
    ) {
        return StrategyDecision.builder()
                .decisionType(isLong ? DecisionType.CLOSE_LONG : DecisionType.CLOSE_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(setupType)
                .side(side)
                .reason(reason)
                .exitReason(reason)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("EXIT", STRATEGY_CODE, side))
                .diagnostics(diag)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal carryScore(BigDecimal z, FundingCarryParams p) {
        // |z| above entry threshold → score scales toward 1 at 2× the threshold.
        if (z == null || p.getEntryZ() == null || p.getEntryZ().signum() <= 0) return ZERO;
        BigDecimal absZ = z.abs();
        BigDecimal excess = absZ.subtract(p.getEntryZ());
        if (excess.signum() <= 0) return ZERO;
        BigDecimal scaled = excess.divide(p.getEntryZ(), 4, RoundingMode.HALF_UP);
        return scaled.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private boolean isMarketVetoed(EnrichedStrategyContext ctx) {
        return ctx.getMarketQualitySnapshot() != null
                && Boolean.FALSE.equals(ctx.getMarketQualitySnapshot().getTradable());
    }

    private BigDecimal resolveAtr(FeatureStore f) {
        return (f != null && f.getAtr() != null && f.getAtr().compareTo(ZERO) > 0) ? f.getAtr() : ONE;
    }

    private Long intervalSeconds(String interval) {
        if (interval == null) return null;
        String s = interval.trim().toLowerCase();
        if (s.isEmpty()) return null;
        return switch (s) {
            case "1m"  -> 60L;
            case "3m"  -> 180L;
            case "5m"  -> 300L;
            case "15m" -> 900L;
            case "30m" -> 1800L;
            case "1h"  -> 3600L;
            case "2h"  -> 7200L;
            case "4h"  -> 14400L;
            case "6h"  -> 21600L;
            case "8h"  -> 28800L;
            case "12h" -> 43200L;
            case "1d"  -> 86400L;
            default    -> parseFlexibleInterval(s);
        };
    }

    /**
     * Fallback parser for novel interval strings — accepts {@code "<n>m"},
     * {@code "<n>h"}, {@code "<n>d"} where n is a positive integer. Returns
     * null when the string can't be interpreted (time-stop is then a no-op
     * and the position relies on z-collapse / ATR stop, which is the same
     * behaviour as before this fix on unknown intervals).
     */
    private Long parseFlexibleInterval(String s) {
        if (s.length() < 2) return null;
        char unit = s.charAt(s.length() - 1);
        String head = s.substring(0, s.length() - 1);
        long n;
        try {
            n = Long.parseLong(head);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n <= 0) return null;
        return switch (unit) {
            case 'm' -> n * 60L;
            case 'h' -> n * 3600L;
            case 'd' -> n * 86400L;
            default  -> null;
        };
    }

    private StrategyDecision hold(EnrichedStrategyContext ctx, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx != null ? ctx.getInterval() : null)
                .signalType(SIGNAL_TYPE_CARRY)
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
                .reason("FCARRY vetoed by risk layer")
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", STRATEGY_CODE, "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }
}
