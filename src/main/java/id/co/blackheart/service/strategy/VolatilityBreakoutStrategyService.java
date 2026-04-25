package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.RiskSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.strategy.VolatilitySnapshot;
import id.co.blackheart.dto.vbo.VboParams;
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

/**
 * Volatility Breakout (VBO).
 *
 * <p>Thesis: low-volatility consolidation precedes directional moves. Liquidity
 * providers tighten quotes during compression; when price breaks the
 * Bollinger band on expanding ATR and rising volume, the breakout side wins
 * because the resting orders that contained price are gone. The edge comes
 * from combining (a) a compression check on the <em>previous</em> bar (BB
 * width pct + optional Bollinger-inside-Keltner squeeze + low ADX), (b) a
 * breakout candle that closes beyond the upper/lower band, (c) volatility
 * expansion (ATR vs prior ATR) and volume participation, (d) a directional
 * close (body + CLV).
 *
 * <p>Stop sits on the opposite side of the breakout candle with an ATR
 * buffer; TP1 is a fixed R-multiple; the runner trails by ATR in phases
 * after break-even — same management shape as TPR for consistency.
 *
 * <p>Params are resolved per-{@code accountStrategyId} via
 * {@link VboStrategyParamService} (defaults &lt; stored DB overrides &lt;
 * backtest-wizard overrides). Defaults live in {@link VboParams#defaults()}.
 *
 * <p>Wire-up: registered in {@link StrategyExecutorFactory} under code {@code "VBO"}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VolatilityBreakoutStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;
    private final VboStrategyParamService vboStrategyParamService;

    // ── Identity ──────────────────────────────────────────────────────────────
    private static final String STRATEGY_CODE    = "VBO";
    private static final String STRATEGY_NAME    = "Volatility Breakout";
    private static final String STRATEGY_VERSION = "v0_research";

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_BREAKOUT   = "VOLATILITY_BREAKOUT";
    private static final String SIGNAL_TYPE_MANAGEMENT = "POSITION_MANAGEMENT";

    private static final String SETUP_LONG_BREAKOUT  = "VBO_LONG_BREAKOUT";
    private static final String SETUP_SHORT_BREAKOUT = "VBO_SHORT_BREAKOUT";
    private static final String SETUP_LONG_BE        = "VBO_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BE       = "VBO_SHORT_BREAK_EVEN";
    private static final String SETUP_LONG_TRAIL     = "VBO_LONG_TRAIL";
    private static final String SETUP_SHORT_TRAIL    = "VBO_SHORT_TRAIL";

    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String TARGET_ALL = "ALL";
    private static final String POSITION_ROLE_TP1    = "TP1";
    private static final String POSITION_ROLE_RUNNER = "RUNNER";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // ── StrategyExecutor contract ─────────────────────────────────────────────

    @Override
    public StrategyRequirements getRequirements() {
        // No bias timeframe — VBO catches regime *transitions* and shouldn't be
        // gated by a 4H trend filter that lags the breakout. Trend alignment
        // (when enabled in VboParams) uses the same-timeframe EMA stack.
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
    public StrategyDecision execute(EnrichedStrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(context, "Invalid context or missing data");
        }

        UUID accountStrategyId = context.getAccountStrategy() != null
                ? context.getAccountStrategy().getAccountStrategyId() : null;
        VboParams p = vboStrategyParamService.getParams(accountStrategyId);

        MarketData md = context.getMarketData();
        FeatureStore f = context.getFeatureStore();
        FeatureStore prev = context.getPreviousFeatureStore();
        PositionSnapshot snap = context.getPositionSnapshot();

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
            StrategyDecision d = tryLongEntry(context, md, f, prev, p);
            if (d != null) return d;
        }

        if (context.isShortAllowed()) {
            StrategyDecision d = tryShortEntry(context, md, f, prev, p);
            if (d != null) return d;
        }

        return hold(context, "No qualified VBO setup");
    }

    // ── Entries ───────────────────────────────────────────────────────────────

    private StrategyDecision tryLongEntry(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f,
            FeatureStore prev, VboParams p
    ) {
        // Gate 1 — compression on the prior candle.
        if (!wasInCompression(prev, p)) {
            log.debug("VBO LONG gate-compression FAIL prevBbWidth={} prevAdx={}",
                    prev != null ? prev.getBbWidth() : null,
                    prev != null ? prev.getAdx() : null);
            return null;
        }

        // Gate 2 — breakout: close above the current upper Bollinger band
        // (and Donchian top, when required).
        BigDecimal upperBb = f.getBbUpperBand();
        if (upperBb == null) return null;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(upperBb) <= 0) {
            log.debug("VBO LONG gate-bb FAIL close={} upperBb={}", close, upperBb);
            return null;
        }
        if (p.isRequireDonchianBreak()
                && (f.getDonchianUpper20() == null || close.compareTo(f.getDonchianUpper20()) <= 0)) {
            log.debug("VBO LONG gate-donchian FAIL close={} donch={}", close, f.getDonchianUpper20());
            return null;
        }

        // Gate 3 — volatility expansion: current bar's range vs prior ATR.
        if (!hasRangeExpansion(md, prev, p)) {
            log.debug("VBO LONG gate-range-expansion FAIL range={} prevAtr={}",
                    rangeOf(md), prev != null ? prev.getAtr() : null);
            return null;
        }

        // Gate 3b — entry-bar ADX band (Goldilocks zone).
        // 15m cohort: <15 lost across 18 trades (WR 17%), 22+ lost across 42
        // trades (WR 31%). The 15–22 band earned +4.11 across 72 trades at
        // WR 53% — clean signal. Compressed-but-just-breaking is the edge;
        // everything outside is either no-trend or already-trending-late.
        if (f.getAdx() == null
                || f.getAdx().compareTo(p.getAdxEntryMin()) < 0
                || f.getAdx().compareTo(p.getAdxEntryMax()) > 0) {
            log.debug("VBO LONG gate-adx FAIL adx={} band={}/{}",
                    f.getAdx(), p.getAdxEntryMin(), p.getAdxEntryMax());
            return null;
        }

        // Gate 4 — volume participation.
        if (f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(p.getRvolMin()) < 0) {
            log.debug("VBO LONG gate-rvol FAIL rvol={}", f.getRelativeVolume20());
            return null;
        }

        // Gate 5 — directional close (body + CLV).
        if (f.getBodyToRangeRatio() == null
                || f.getBodyToRangeRatio().compareTo(p.getBodyRatioMin()) < 0) {
            log.debug("VBO LONG gate-body FAIL body={}", f.getBodyToRangeRatio());
            return null;
        }
        if (f.getCloseLocationValue() == null
                || f.getCloseLocationValue().compareTo(p.getClvMin()) < 0
                || f.getCloseLocationValue().compareTo(p.getClvMax()) > 0) {
            // Upper bound rejects bars that closed pinned to the high — same
            // exhaustion rationale as TPR.
            log.debug("VBO LONG gate-clv FAIL clv={} band={}/{}",
                    f.getCloseLocationValue(), p.getClvMin(), p.getClvMax());
            return null;
        }

        // Gate 6 — RSI sanity: avoid chasing already-extended runs.
        if (f.getRsi() != null && f.getRsi().compareTo(p.getLongRsiMax()) > 0) {
            log.debug("VBO LONG gate-rsi FAIL rsi={} max={}", f.getRsi(), p.getLongRsiMax());
            return null;
        }

        // Gate 7 — optional trend alignment (close above EMA50, EMA50 not
        // strongly down). Off by default — VBO is meant to catch regime
        // transitions where higher TF trend hasn't flipped yet.
        if (p.isRequireTrendAlignment() && !hasBullishTrendAlignment(md, f, p)) {
            log.debug("VBO LONG gate-trend FAIL");
            return null;
        }

        // ── Sizing & stop: structural (below breakout candle low) with ATR buffer.
        BigDecimal atr = resolveAtr(f);
        BigDecimal low = strategyHelper.safe(md.getLowPrice());
        BigDecimal entry = close;
        BigDecimal stop = low.subtract(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = entry.subtract(stop);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal maxAllowedRisk = entry.multiply(p.getMaxEntryRiskPct());
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) {
            log.debug("VBO LONG skipped — stop too wide risk={}%",
                    riskPerUnit.divide(entry, 4, RoundingMode.HALF_UP).multiply(HUNDRED));
            return null;
        }

        BigDecimal tp1 = entry.add(riskPerUnit.multiply(p.getTp1R()));

        BigDecimal score = calculateLongSignalScore(md, f, prev);
        if (score.compareTo(p.getMinSignalScore()) < 0) {
            log.debug("VBO LONG score FAIL score={} min={}", score, p.getMinSignalScore());
            return null;
        }

        BigDecimal notional = strategyHelper.calculateEntryNotional(ctx, SIDE_LONG);
        if (notional.compareTo(ZERO) <= 0) return hold(ctx, "VBO long notional zero");

        log.info("VBO LONG ENTRY | time={} close={} upperBb={} stop={} tp1={} risk%={} score={}",
                md.getEndTime(), entry, upperBb, stop, tp1,
                riskPerUnit.divide(entry, 4, RoundingMode.HALF_UP).multiply(HUNDRED), score);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_BREAKOUT)
                .setupType(SETUP_LONG_BREAKOUT)
                .side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(ctx, f))
                .reason("VBO long: BB-width compression broke up on ATR expansion + volume + bullish close")
                .signalScore(score)
                .confidenceScore(score)
                .regimeScore(resolveRegimeScore(ctx))
                .riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .notionalSize(notional)
                .stopLossPrice(stop)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_TP1_RUNNER)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx())
                .entryAtr(f.getAtr())
                .entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "VBO", "LONG", "BREAKOUT", STRATEGY_VERSION))
                .diagnostics(Map.of(
                        "module", "VolatilityBreakoutStrategyService",
                        "entry", entry, "stop", stop, "tp1", tp1,
                        "upperBb", upperBb,
                        "prevBbWidth", prev != null && prev.getBbWidth() != null ? prev.getBbWidth() : ZERO,
                        "atr", atr,
                        "rvol", strategyHelper.safe(f.getRelativeVolume20())))
                .build();
    }

    private StrategyDecision tryShortEntry(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f,
            FeatureStore prev, VboParams p
    ) {
        if (!wasInCompression(prev, p)) return null;

        BigDecimal lowerBb = f.getBbLowerBand();
        if (lowerBb == null) return null;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(lowerBb) >= 0) return null;

        if (p.isRequireDonchianBreak()
                && (f.getDonchianLower20() == null || close.compareTo(f.getDonchianLower20()) >= 0)) {
            return null;
        }

        if (!hasRangeExpansion(md, prev, p)) return null;

        if (f.getAdx() == null
                || f.getAdx().compareTo(p.getAdxEntryMin()) < 0
                || f.getAdx().compareTo(p.getAdxEntryMax()) > 0) {
            return null;
        }

        if (f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(p.getRvolMin()) < 0) {
            return null;
        }

        if (f.getBodyToRangeRatio() == null
                || f.getBodyToRangeRatio().compareTo(p.getBodyRatioMin()) < 0) {
            return null;
        }

        // Mirror the LONG CLV band onto the bottom of the candle.
        BigDecimal clv = f.getCloseLocationValue();
        BigDecimal clvShortMax = ONE.subtract(p.getClvMin());
        BigDecimal clvShortMin = ONE.subtract(p.getClvMax());
        if (clv == null || clv.compareTo(clvShortMax) > 0 || clv.compareTo(clvShortMin) < 0) {
            log.debug("VBO SHORT gate-clv FAIL clv={} band={}/{}", clv, clvShortMin, clvShortMax);
            return null;
        }

        if (f.getRsi() != null && f.getRsi().compareTo(p.getShortRsiMin()) < 0) {
            log.debug("VBO SHORT gate-rsi FAIL rsi={} min={}", f.getRsi(), p.getShortRsiMin());
            return null;
        }

        if (p.isRequireTrendAlignment() && !hasBearishTrendAlignment(md, f, p)) {
            log.debug("VBO SHORT gate-trend FAIL");
            return null;
        }

        BigDecimal atr = resolveAtr(f);
        BigDecimal high = strategyHelper.safe(md.getHighPrice());
        BigDecimal entry = close;
        BigDecimal stop = high.add(atr.multiply(p.getStopAtrBuffer()));
        BigDecimal riskPerUnit = stop.subtract(entry);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal maxAllowedRisk = entry.multiply(p.getMaxEntryRiskPct());
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) return null;

        BigDecimal tp1 = entry.subtract(riskPerUnit.multiply(p.getTp1R()));

        BigDecimal score = calculateShortSignalScore(md, f, prev);
        if (score.compareTo(p.getMinSignalScore()) < 0) return null;

        BigDecimal notional = strategyHelper.calculateEntryNotional(ctx, SIDE_SHORT);
        if (notional.compareTo(ZERO) <= 0) return hold(ctx, "VBO short notional zero");

        log.info("VBO SHORT ENTRY | time={} close={} lowerBb={} stop={} tp1={} risk%={} score={}",
                md.getEndTime(), entry, lowerBb, stop, tp1,
                riskPerUnit.divide(entry, 4, RoundingMode.HALF_UP).multiply(HUNDRED), score);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_BREAKOUT)
                .setupType(SETUP_SHORT_BREAKOUT)
                .side(SIDE_SHORT)
                .regimeLabel(resolveRegimeLabel(ctx, f))
                .reason("VBO short: BB-width compression broke down on ATR expansion + volume + bearish close")
                .signalScore(score)
                .confidenceScore(score)
                .regimeScore(resolveRegimeScore(ctx))
                .riskMultiplier(resolveRiskMultiplier(ctx))
                .jumpRiskScore(resolveJumpRisk(ctx))
                .positionSize(notional)
                .stopLossPrice(stop)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE_TP1_RUNNER)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(f.getAdx())
                .entryAtr(f.getAtr())
                .entryRsi(f.getRsi())
                .entryTrendRegime(f.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "VBO", "SHORT", "BREAKOUT", STRATEGY_VERSION))
                .diagnostics(Map.of(
                        "module", "VolatilityBreakoutStrategyService",
                        "entry", entry, "stop", stop, "tp1", tp1,
                        "lowerBb", lowerBb,
                        "prevBbWidth", prev != null && prev.getBbWidth() != null ? prev.getBbWidth() : ZERO,
                        "atr", atr,
                        "rvol", strategyHelper.safe(f.getRelativeVolume20())))
                .build();
    }

    // ── Position management ───────────────────────────────────────────────────

    private StrategyDecision managePosition(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f,
            PositionSnapshot snap, VboParams p
    ) {
        String side = snap.getSide();
        String role = snap.getPositionRole();
        if (side == null) return hold(ctx, "VBO manage: unknown side");

        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);
        boolean isRunner = POSITION_ROLE_RUNNER.equalsIgnoreCase(role);

        BigDecimal entry = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal initStop = snap.getInitialStopLossPrice() != null
                ? snap.getInitialStopLossPrice() : curStop;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal initRisk = isLong ? entry.subtract(initStop) : initStop.subtract(entry);
        if (initRisk.compareTo(ZERO) <= 0) return hold(ctx, "VBO manage: invalid init risk");

        BigDecimal move = isLong ? close.subtract(entry) : entry.subtract(close);
        if (move.compareTo(ZERO) <= 0) return hold(ctx, "VBO manage: not in profit");

        BigDecimal rMultiple = move.divide(initRisk, 8, RoundingMode.HALF_UP);

        // ── TP1 leg: move stop to break-even once trade is in profit enough. ─
        if (!isRunner) {
            BigDecimal beTrigger = initRisk.multiply(p.getBreakEvenR());
            if (move.compareTo(beTrigger) < 0) return hold(ctx, "VBO BE not ready");

            boolean alreadyAtBe = isLong ? curStop.compareTo(entry) >= 0 : curStop.compareTo(entry) <= 0;
            if (alreadyAtBe) return hold(ctx, "VBO stop already at BE");

            return buildManagementDecision(
                    ctx, side, isLong ? SETUP_LONG_BE : SETUP_SHORT_BE,
                    entry, snap.getTakeProfitPrice(),
                    "Move TP1 leg to break-even at " + p.getBreakEvenR() + "R",
                    Map.of("entry", entry, "curStop", curStop, "close", close, "rMultiple", rMultiple),
                    POSITION_ROLE_TP1
            );
        }

        // ── Runner leg: phase-based ATR trail. ───────────────────────────────
        BigDecimal atr = resolveAtr(f);
        BigDecimal candidate = null;

        if (rMultiple.compareTo(p.getRunnerPhase3R()) >= 0) {
            BigDecimal atrTrail = isLong
                    ? close.subtract(atr.multiply(p.getRunnerAtrPhase3()))
                    : close.add(atr.multiply(p.getRunnerAtrPhase3()));
            BigDecimal lockStop = isLong
                    ? entry.add(initRisk.multiply(p.getRunnerLockPhase3R()))
                    : entry.subtract(initRisk.multiply(p.getRunnerLockPhase3R()));
            candidate = isLong ? atrTrail.max(lockStop) : atrTrail.min(lockStop);
        } else if (rMultiple.compareTo(p.getRunnerPhase2R()) >= 0) {
            BigDecimal atrTrail = isLong
                    ? close.subtract(atr.multiply(p.getRunnerAtrPhase2()))
                    : close.add(atr.multiply(p.getRunnerAtrPhase2()));
            BigDecimal lockStop = isLong
                    ? entry.add(initRisk.multiply(p.getRunnerLockPhase2R()))
                    : entry.subtract(initRisk.multiply(p.getRunnerLockPhase2R()));
            candidate = isLong ? atrTrail.max(lockStop) : atrTrail.min(lockStop);
        } else if (rMultiple.compareTo(p.getRunnerBreakEvenR()) >= 0) {
            boolean alreadyAtBe = isLong ? curStop.compareTo(entry) >= 0 : curStop.compareTo(entry) <= 0;
            if (!alreadyAtBe) candidate = entry;
        }

        if (candidate == null) return hold(ctx, "VBO runner not ready");

        boolean improved = isLong
                ? candidate.compareTo(curStop) > 0
                : candidate.compareTo(curStop) < 0;
        if (!improved) return hold(ctx, "VBO runner stop already optimal");

        return buildTrailDecision(
                ctx, side, isLong ? SETUP_LONG_TRAIL : SETUP_SHORT_TRAIL,
                candidate, snap.getTakeProfitPrice(),
                "VBO runner trail at " + rMultiple + "R",
                Map.of("rMultiple", rMultiple, "candidate", candidate,
                        "curStop", curStop, "close", close, "atr", atr),
                POSITION_ROLE_RUNNER
        );
    }

    // ── Compression / expansion / trend helpers ───────────────────────────────

    /**
     * Compression on the previous bar:
     *   1. {@code prev.bbWidth / prev.price} below {@link VboParams#getCompressionBbWidthPctMax()}
     *      (BB has narrowed relative to price), and
     *   2. {@code prev.adx <= compressionAdxMax} (no strong trend was active), and
     *   3. optionally Bollinger inside Keltner squeeze (KC width > BB width).
     */
    private boolean wasInCompression(FeatureStore prev, VboParams p) {
        if (prev == null) return false;
        BigDecimal bbWidth = prev.getBbWidth();
        BigDecimal price = prev.getPrice();
        if (bbWidth == null || price == null || price.compareTo(ZERO) <= 0) return false;

        BigDecimal bbWidthPct = bbWidth.divide(price, 8, RoundingMode.HALF_UP);
        if (bbWidthPct.compareTo(p.getCompressionBbWidthPctMax()) > 0) return false;

        if (prev.getAdx() != null && prev.getAdx().compareTo(p.getCompressionAdxMax()) > 0) {
            return false;
        }

        if (p.isRequireKcSqueeze()) {
            BigDecimal kcWidth = prev.getKcWidth();
            if (kcWidth == null || kcWidth.compareTo(bbWidth) <= 0) return false;
        }

        return true;
    }

    /**
     * Current candle's true range divided by the prior bar's smoothed ATR.
     *
     * <p>v0 used current ATR / prev ATR — but ATR is Wilder-smoothed (14), so
     * even a violent breakout candle barely shifts the ratio bar-over-bar
     * (TR_today ≈ 5× prev ATR is needed for a 1.30 ATR-ratio). Comparing the
     * raw bar range to prior ATR is the actual "expansion" signal we want
     * and matches how breakout traders read a chart.
     */
    private boolean hasRangeExpansion(MarketData md, FeatureStore prev, VboParams p) {
        if (md == null || prev == null) return false;
        BigDecimal range = rangeOf(md);
        BigDecimal prevAtr = prev.getAtr();
        if (range == null || prevAtr == null || prevAtr.compareTo(ZERO) <= 0) return false;
        BigDecimal ratio = range.divide(prevAtr, 8, RoundingMode.HALF_UP);
        return ratio.compareTo(p.getAtrExpansionMin()) >= 0;
    }

    private BigDecimal rangeOf(MarketData md) {
        if (md == null) return null;
        BigDecimal high = md.getHighPrice();
        BigDecimal low = md.getLowPrice();
        if (high == null || low == null) return null;
        return high.subtract(low);
    }

    private boolean hasBullishTrendAlignment(MarketData md, FeatureStore f, VboParams p) {
        if (f.getEma50() == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) <= 0) return false;
        // Allow neutral or rising EMA50 — we don't require a positive slope on
        // a regime-transition strategy, just no strong opposing slope.
        return f.getEma50Slope() == null
                || f.getEma50Slope().compareTo(p.getEma50SlopeMin().negate()) >= 0;
    }

    private boolean hasBearishTrendAlignment(MarketData md, FeatureStore f, VboParams p) {
        if (f.getEma50() == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) >= 0) return false;
        return f.getEma50Slope() == null
                || f.getEma50Slope().compareTo(p.getEma50SlopeMin()) <= 0;
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private BigDecimal calculateLongSignalScore(MarketData md, FeatureStore f, FeatureStore prev) {
        BigDecimal clvScore = f.getCloseLocationValue() != null ? f.getCloseLocationValue() : ZERO;
        BigDecimal bodyScore = f.getBodyToRangeRatio() != null ? f.getBodyToRangeRatio() : ZERO;
        BigDecimal volScore = f.getRelativeVolume20() != null
                ? f.getRelativeVolume20().divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP).min(ONE)
                : ZERO;
        BigDecimal expansionScore = rangeExpansionScore(md, prev);

        // Equal weights across the four components — tune in v0.1+.
        BigDecimal score = clvScore.multiply(new BigDecimal("0.25"))
                .add(bodyScore.multiply(new BigDecimal("0.25")))
                .add(volScore.multiply(new BigDecimal("0.25")))
                .add(expansionScore.multiply(new BigDecimal("0.25")));
        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortSignalScore(MarketData md, FeatureStore f, FeatureStore prev) {
        BigDecimal clvScore = f.getCloseLocationValue() != null
                ? ONE.subtract(f.getCloseLocationValue()) : ZERO;
        BigDecimal bodyScore = f.getBodyToRangeRatio() != null ? f.getBodyToRangeRatio() : ZERO;
        BigDecimal volScore = f.getRelativeVolume20() != null
                ? f.getRelativeVolume20().divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP).min(ONE)
                : ZERO;
        BigDecimal expansionScore = rangeExpansionScore(md, prev);

        BigDecimal score = clvScore.multiply(new BigDecimal("0.25"))
                .add(bodyScore.multiply(new BigDecimal("0.25")))
                .add(volScore.multiply(new BigDecimal("0.25")))
                .add(expansionScore.multiply(new BigDecimal("0.25")));
        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    /** Range / prev-ATR normalised to [0,1] over the band [1.0, 3.0] —
     *  1.0× = 0, 3.0× = 1.0. Beyond 3× pins to 1. Aligned with the
     *  hasRangeExpansion gate so the score and the gate read the same signal. */
    private BigDecimal rangeExpansionScore(MarketData md, FeatureStore prev) {
        if (md == null || prev == null || prev.getAtr() == null
                || prev.getAtr().compareTo(ZERO) <= 0) {
            return ZERO;
        }
        BigDecimal range = rangeOf(md);
        if (range == null) return ZERO;
        BigDecimal ratio = range.divide(prev.getAtr(), 8, RoundingMode.HALF_UP);
        BigDecimal scaled = ratio.subtract(ONE).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
        if (scaled.compareTo(ZERO) < 0) return ZERO;
        if (scaled.compareTo(ONE) > 0) return ONE;
        return scaled;
    }

    // ── Decision builders ─────────────────────────────────────────────────────

    private StrategyDecision buildManagementDecision(
            EnrichedStrategyContext ctx, String side, String setupType,
            BigDecimal newStop, BigDecimal takeProfit, String reason,
            Map<String, Object> diag, String targetRole
    ) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(setupType)
                .side(side)
                .reason(reason)
                .stopLossPrice(newStop)
                .takeProfitPrice1(takeProfit)
                .targetPositionRole(targetRole)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", STRATEGY_CODE, side, "BREAK_EVEN"))
                .diagnostics(diag)
                .build();
    }

    private StrategyDecision buildTrailDecision(
            EnrichedStrategyContext ctx, String side, String setupType,
            BigDecimal newStop, BigDecimal takeProfit, String reason,
            Map<String, Object> diag, String targetRole
    ) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(setupType)
                .side(side)
                .reason(reason)
                .stopLossPrice(newStop)
                .trailingStopPrice(newStop)
                .takeProfitPrice1(takeProfit)
                .targetPositionRole(targetRole)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", STRATEGY_CODE, side, "TRAIL"))
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
                .signalType(SIGNAL_TYPE_BREAKOUT)
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
                .reason("VBO vetoed by risk layer")
                .jumpRiskScore(ctx != null ? resolveJumpRisk(ctx) : ZERO)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", STRATEGY_CODE, "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }

    // ── Snapshot resolvers ────────────────────────────────────────────────────

    private boolean isMarketVetoed(EnrichedStrategyContext ctx) {
        return ctx.getMarketQualitySnapshot() != null
                && Boolean.FALSE.equals(ctx.getMarketQualitySnapshot().getTradable());
    }

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

}
