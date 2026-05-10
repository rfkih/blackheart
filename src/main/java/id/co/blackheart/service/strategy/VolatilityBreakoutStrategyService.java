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

    private static final String DIAG_ENTRY = "entry";

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

    // ── Entry gate helpers ────────────────────────────────────────────────────

    private boolean passesAdxBandGate(FeatureStore f, VboParams p) {
        if (f.getAdx() == null
                || f.getAdx().compareTo(p.getAdxEntryMin()) < 0
                || f.getAdx().compareTo(p.getAdxEntryMax()) > 0) {
            log.debug("VBO gate-adx FAIL adx={} band={}/{}", f.getAdx(), p.getAdxEntryMin(), p.getAdxEntryMax());
            return false;
        }
        return true;
    }

    private boolean passesBodyClvLongGate(FeatureStore f, VboParams p) {
        if (f.getBodyToRangeRatio() == null
                || f.getBodyToRangeRatio().compareTo(p.getBodyRatioMin()) < 0) {
            log.debug("VBO LONG gate-body FAIL body={}", f.getBodyToRangeRatio());
            return false;
        }
        if (f.getCloseLocationValue() == null
                || f.getCloseLocationValue().compareTo(p.getClvMin()) < 0
                || f.getCloseLocationValue().compareTo(p.getClvMax()) > 0) {
            log.debug("VBO LONG gate-clv FAIL clv={} band={}/{}",
                    f.getCloseLocationValue(), p.getClvMin(), p.getClvMax());
            return false;
        }
        return true;
    }

    private boolean passesBodyClvShortGate(FeatureStore f, VboParams p) {
        if (f.getBodyToRangeRatio() == null
                || f.getBodyToRangeRatio().compareTo(p.getBodyRatioMin()) < 0) {
            return false;
        }
        BigDecimal clv = f.getCloseLocationValue();
        BigDecimal clvShortMax = ONE.subtract(p.getClvMin());
        BigDecimal clvShortMin = ONE.subtract(p.getClvMax());
        if (clv == null || clv.compareTo(clvShortMax) > 0 || clv.compareTo(clvShortMin) < 0) {
            log.debug("VBO SHORT gate-clv FAIL clv={} band={}/{}", clv, clvShortMin, clvShortMax);
            return false;
        }
        return true;
    }

    private boolean passesLongGates(MarketData md, FeatureStore f, FeatureStore prev, VboParams p) {
        if (!checkLongCompression(prev, p)) return false;
        BigDecimal upperBb = f.getBbUpperBand();
        if (upperBb == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(upperBb) <= 0) {
            log.debug("VBO LONG gate-bb FAIL close={} upperBb={}", close, upperBb);
            return false;
        }
        if (!checkLongDonchian(close, f, p)) return false;
        if (!checkLongRangeExpansion(md, prev, p)) return false;
        if (!passesAdxBandGate(f, p)) return false;
        if (!checkLongRvol(f, p)) return false;
        if (!passesBodyClvLongGate(f, p)) return false;
        if (!checkLongRsi(f, p)) return false;
        return checkLongTrendAlignment(md, f, p);
    }

    private boolean checkLongCompression(FeatureStore prev, VboParams p) {
        if (wasInCompression(prev, p)) return true;
        log.debug("VBO LONG gate-compression FAIL prevBbWidth={} prevAdx={}",
                prev != null ? prev.getBbWidth() : null, prev != null ? prev.getAdx() : null);
        return false;
    }

    private boolean checkLongDonchian(BigDecimal close, FeatureStore f, VboParams p) {
        if (!p.isRequireDonchianBreak()) return true;
        BigDecimal donch = f.getDonchianUpper20();
        if (donch != null && close.compareTo(donch) > 0) return true;
        log.debug("VBO LONG gate-donchian FAIL close={} donch={}", close, donch);
        return false;
    }

    private boolean checkLongRangeExpansion(MarketData md, FeatureStore prev, VboParams p) {
        if (hasRangeExpansion(md, prev, p)) return true;
        log.debug("VBO LONG gate-range-expansion FAIL range={} prevAtr={}",
                rangeOf(md), prev != null ? prev.getAtr() : null);
        return false;
    }

    private boolean checkLongRvol(FeatureStore f, VboParams p) {
        if (f.getRelativeVolume20() != null && f.getRelativeVolume20().compareTo(p.getRvolMin()) >= 0) return true;
        log.debug("VBO LONG gate-rvol FAIL rvol={}", f.getRelativeVolume20());
        return false;
    }

    private boolean checkLongRsi(FeatureStore f, VboParams p) {
        if (f.getRsi() == null || f.getRsi().compareTo(p.getLongRsiMax()) <= 0) return true;
        log.debug("VBO LONG gate-rsi FAIL rsi={} max={}", f.getRsi(), p.getLongRsiMax());
        return false;
    }

    private boolean checkLongTrendAlignment(MarketData md, FeatureStore f, VboParams p) {
        if (!p.isRequireTrendAlignment() || hasBullishTrendAlignment(md, f, p)) return true;
        log.debug("VBO LONG gate-trend FAIL");
        return false;
    }

    private boolean passesShortGates(MarketData md, FeatureStore f, FeatureStore prev, VboParams p) {
        if (!wasInCompression(prev, p)) return false;
        BigDecimal lowerBb = f.getBbLowerBand();
        if (lowerBb == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(lowerBb) >= 0) return false;
        if (p.isRequireDonchianBreak()
                && (f.getDonchianLower20() == null || close.compareTo(f.getDonchianLower20()) >= 0)) {
            return false;
        }
        if (!hasRangeExpansion(md, prev, p)) return false;
        if (!passesAdxBandGate(f, p)) return false;
        if (f.getRelativeVolume20() == null || f.getRelativeVolume20().compareTo(p.getRvolMin()) < 0) return false;
        if (!passesBodyClvShortGate(f, p)) return false;
        if (f.getRsi() != null && f.getRsi().compareTo(p.getShortRsiMin()) < 0) {
            log.debug("VBO SHORT gate-rsi FAIL rsi={} min={}", f.getRsi(), p.getShortRsiMin());
            return false;
        }
        if (p.isRequireTrendAlignment() && !hasBearishTrendAlignment(md, f, p)) {
            log.debug("VBO SHORT gate-trend FAIL");
            return false;
        }
        return true;
    }

    // ── Entries ───────────────────────────────────────────────────────────────

    private StrategyDecision tryLongEntry(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f,
            FeatureStore prev, VboParams p
    ) {
        if (!passesLongGates(md, f, prev, p)) return null;

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal upperBb = f.getBbUpperBand();
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

        BigDecimal notional = strategyHelper.calculateLongEntryNotional(ctx, entry, stop);
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
                        DIAG_ENTRY, entry, "stop", stop, "tp1", tp1,
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
        if (!passesShortGates(md, f, prev, p)) return null;

        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        BigDecimal lowerBb = f.getBbLowerBand();
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

        // SHORT executor reads `positionSize` (BTC qty) and matches it against
        // the BTC balance. V55 — calculateShortEntryQty picks risk-based vs
        // legacy allocation sizing based on the AccountStrategy toggle.
        BigDecimal positionSize = strategyHelper.calculateShortEntryQty(ctx, entry, stop);
        if (positionSize.compareTo(ZERO) <= 0) return hold(ctx, "VBO short position size zero");

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
                .positionSize(positionSize)
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
                .tags(List.of("ENTRY", "VBO", SIDE_SHORT, "BREAKOUT", STRATEGY_VERSION))
                .diagnostics(Map.of(
                        "module", "VolatilityBreakoutStrategyService",
                        DIAG_ENTRY, entry, "stop", stop, "tp1", tp1,
                        "lowerBb", lowerBb,
                        "prevBbWidth", prev != null && prev.getBbWidth() != null ? prev.getBbWidth() : ZERO,
                        "atr", atr,
                        "rvol", strategyHelper.safe(f.getRelativeVolume20())))
                .build();
    }

    // ── Position management ───────────────────────────────────────────────────

    private record PositionState(
            String side, boolean isLong, BigDecimal close,
            BigDecimal entry, BigDecimal curStop,
            BigDecimal initRisk, BigDecimal move, BigDecimal rMultiple
    ) {}

    private StrategyDecision managePosition(
            EnrichedStrategyContext ctx, MarketData md, FeatureStore f,
            PositionSnapshot snap, VboParams p
    ) {
        String side = snap.getSide();
        if (side == null) return hold(ctx, "VBO manage: unknown side");

        boolean isLong = SIDE_LONG.equalsIgnoreCase(side);
        boolean isRunner = POSITION_ROLE_RUNNER.equalsIgnoreCase(snap.getPositionRole());

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
        PositionState st = new PositionState(side, isLong, close, entry, curStop, initRisk, move, rMultiple);
        return isRunner ? manageRunnerLeg(ctx, st, f, snap, p) : manageTp1Leg(ctx, st, snap, p);
    }

    private StrategyDecision manageTp1Leg(
            EnrichedStrategyContext ctx, PositionState st, PositionSnapshot snap, VboParams p
    ) {
        BigDecimal beTrigger = st.initRisk().multiply(p.getBreakEvenR());
        if (st.move().compareTo(beTrigger) < 0) return hold(ctx, "VBO BE not ready");

        boolean alreadyAtBe = st.isLong()
                ? st.curStop().compareTo(st.entry()) >= 0
                : st.curStop().compareTo(st.entry()) <= 0;
        if (alreadyAtBe) return hold(ctx, "VBO stop already at BE");

        ManagementTarget beTarget = new ManagementTarget(
                st.isLong() ? SETUP_LONG_BE : SETUP_SHORT_BE,
                st.entry(), snap.getTakeProfitPrice(), POSITION_ROLE_TP1);
        return buildManagementDecision(ctx, st.side(), beTarget,
                "Move TP1 leg to break-even at " + p.getBreakEvenR() + "R",
                Map.of(DIAG_ENTRY, st.entry(), "curStop", st.curStop(),
                        "close", st.close(), "rMultiple", st.rMultiple()));
    }

    private StrategyDecision manageRunnerLeg(
            EnrichedStrategyContext ctx, PositionState st, FeatureStore f,
            PositionSnapshot snap, VboParams p
    ) {
        BigDecimal atr = resolveAtr(f);
        BigDecimal candidate = computeRunnerCandidate(st, atr, p);
        if (candidate == null) return hold(ctx, "VBO runner not ready");

        boolean improved = st.isLong()
                ? candidate.compareTo(st.curStop()) > 0
                : candidate.compareTo(st.curStop()) < 0;
        if (!improved) return hold(ctx, "VBO runner stop already optimal");

        ManagementTarget trailTarget = new ManagementTarget(
                st.isLong() ? SETUP_LONG_TRAIL : SETUP_SHORT_TRAIL,
                candidate, snap.getTakeProfitPrice(), POSITION_ROLE_RUNNER);
        return buildTrailDecision(ctx, st.side(), trailTarget,
                "VBO runner trail at " + st.rMultiple() + "R",
                Map.of("rMultiple", st.rMultiple(), "candidate", candidate,
                        "curStop", st.curStop(), "close", st.close(), "atr", atr));
    }

    private BigDecimal computeRunnerCandidate(PositionState st, BigDecimal atr, VboParams p) {
        if (st.rMultiple().compareTo(p.getRunnerPhase3R()) >= 0) {
            return computePhaseStop(st, atr, p.getRunnerAtrPhase3(), p.getRunnerLockPhase3R());
        }
        if (st.rMultiple().compareTo(p.getRunnerPhase2R()) >= 0) {
            return computePhaseStop(st, atr, p.getRunnerAtrPhase2(), p.getRunnerLockPhase2R());
        }
        if (st.rMultiple().compareTo(p.getRunnerBreakEvenR()) >= 0) {
            boolean alreadyAtBe = st.isLong()
                    ? st.curStop().compareTo(st.entry()) >= 0
                    : st.curStop().compareTo(st.entry()) <= 0;
            return alreadyAtBe ? null : st.entry();
        }
        return null;
    }

    private BigDecimal computePhaseStop(PositionState st, BigDecimal atr, BigDecimal atrMult, BigDecimal lockMult) {
        BigDecimal atrTrail = st.isLong()
                ? st.close().subtract(atr.multiply(atrMult))
                : st.close().add(atr.multiply(atrMult));
        BigDecimal lockStop = st.isLong()
                ? st.entry().add(st.initRisk().multiply(lockMult))
                : st.entry().subtract(st.initRisk().multiply(lockMult));
        return st.isLong() ? atrTrail.max(lockStop) : atrTrail.min(lockStop);
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
        if (!(f.getEma50Slope() == null
                || f.getEma50Slope().compareTo(p.getEma50SlopeMin().negate()) >= 0)) return false;
        return !p.isRequireSlope200Gate() || f.getSlope200() == null
                || f.getSlope200().compareTo(p.getSlope200Min()) >= 0;
    }

    private boolean hasBearishTrendAlignment(MarketData md, FeatureStore f, VboParams p) {
        if (f.getEma50() == null) return false;
        BigDecimal close = strategyHelper.safe(md.getClosePrice());
        if (close.compareTo(f.getEma50()) >= 0) return false;
        if (!(f.getEma50Slope() == null
                || f.getEma50Slope().compareTo(p.getEma50SlopeMin()) <= 0)) return false;
        return !p.isRequireSlope200Gate() || f.getSlope200() == null
                || f.getSlope200().compareTo(p.getSlope200Min().negate()) <= 0;
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

    private record ManagementTarget(
            String setupType, BigDecimal newStop, BigDecimal takeProfit, String targetRole
    ) {}

    private StrategyDecision buildManagementDecision(
            EnrichedStrategyContext ctx, String side, ManagementTarget t, String reason, Map<String, Object> diag
    ) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(t.setupType())
                .side(side)
                .reason(reason)
                .stopLossPrice(t.newStop())
                .takeProfitPrice1(t.takeProfit())
                .targetPositionRole(t.targetRole())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", STRATEGY_CODE, side, "BREAK_EVEN"))
                .diagnostics(diag)
                .build();
    }

    private StrategyDecision buildTrailDecision(
            EnrichedStrategyContext ctx, String side, ManagementTarget t, String reason, Map<String, Object> diag
    ) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(ctx.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(t.setupType())
                .side(side)
                .reason(reason)
                .stopLossPrice(t.newStop())
                .trailingStopPrice(t.newStop())
                .takeProfitPrice1(t.takeProfit())
                .targetPositionRole(t.targetRole())
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
