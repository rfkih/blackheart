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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║          VCB — Volatility Compression Breakout  (v1)                   ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║                                                                          ║
 * ║  THESIS                                                                  ║
 * ║  ──────                                                                  ║
 * ║  BTC/USDT exhibits a repeatable compression → expansion cycle.           ║
 * ║  When volatility compresses (BB narrows inside KC, ATR drops below       ║
 * ║  its median, ER falls toward 0), potential energy builds. The first      ║
 * ║  directional break out of compression, confirmed by volume and a         ║
 * ║  higher-TF bias, has historically higher follow-through than entries     ║
 * ║  made mid-impulse.                                                        ║
 * ║                                                                          ║
 * ║  EDGE vs PRIOR STRATEGIES                                                ║
 * ║  ─────────────────────────                                               ║
 * ║  Prior strategies (TsMomV1, TrendPullback) entered AFTER confirmation    ║
 * ║  stacking: RSI + MACD + ADX + EMA all had to agree. That means the       ║
 * ║  move was already 60-70% complete. This strategy enters at the START     ║
 * ║  of a new impulse, giving structurally tighter stops and higher RR.      ║
 * ║                                                                          ║
 * ║  SIGNAL ARCHITECTURE                                                     ║
 * ║  ──────────────────────                                                  ║
 * ║  Layer 1 — 4H Bias (direction gate)                                      ║
 * ║    • EMA50 > EMA200 + close > EMA200  → BULL bias (allows pullbacks)     ║
 * ║    • Signed ER20 (4H) > 0.05 confirms directional efficiency (soft)      ║
 * ║                                                                          ║
 * ║  Layer 2 — 1H Compression Detection (setup gate, on PREVIOUS candle)     ║
 * ║    • BB width < KC width * 0.95  →  squeeze active (BB inside KC)        ║
 * ║    • ATR ratio < 1.00            →  ATR at or below 20-period median      ║
 * ║    • ER20 < 0.30                 →  low directional efficiency            ║
 * ║    • 2 of 3 required (bbSqueeze + atrCompressed are anti-correlated)     ║
 * ║                                                                          ║
 * ║  Layer 3 — 1H Breakout Candle (trigger)                                  ║
 * ║    • LONG:  close > Donchian upper 20  AND  relVol20 > 1.20              ║
 * ║    • SHORT: close < Donchian lower 20  AND  relVol20 > 1.20              ║
 * ║    • Breakout candle body > 0.45 of candle range (conviction)            ║
 * ║    • No 1H EMA50 check — 4H bias gate already covers trend alignment     ║
 * ║                                                                          ║
 * ║  Layer 4 — 15m Entry Refinement (optional, timing)                       ║
 * ║    • After 1H breakout candle closes, wait for first 15m candle          ║
 * ║      that pulls back to broken level ±0.5 ATR (15m)                      ║
 * ║    • If no pullback within 3 candles → enter at market (chase cap)       ║
 * ║    • NOTE: In current architecture, 15m and 1H both trigger the          ║
 * ║      strategy independently. When running on 1H, the strategy enters     ║
 * ║      at breakout candle close. When running on 15m, it attempts the      ║
 * ║      pullback entry. Deploy both on separate AccountStrategy rows.       ║
 * ║                                                                          ║
 * ║  EXIT STRUCTURE                                                           ║
 * ║  ──────────────                                                           ║
 * ║  Stop:    Below breakout candle low (long) / above high (short)          ║
 * ║           + 0.30 ATR buffer. Hard structural stop.                       ║
 * ║  TP1:     1.8R — close 40% of position. Justified: at 1.8R you've       ║
 * ║           covered your risk 1.8x. Locks profit while runner lives.       ║
 * ║  Runner:  60% of position. Three-phase ATR trail:                        ║
 * ║           Phase 0 (0–1R):   No trail. Hold stop at entry stop.           ║
 * ║           Phase 1 (1–2R):   Move stop to break-even. Lock 0R.            ║
 * ║           Phase 2 (2–3R):   Trail at 1.5 ATR. Lock 0.8R profit.         ║
 * ║           Phase 3 (3R+):    Trail at 0.8 ATR. Lock 2.0R profit.          ║
 * ║           Rationale: give the trade room early, tighten as it extends.   ║
 * ║                                                                          ║
 * ║  RISK PARAMETERS                                                          ║
 * ║  ────────────────                                                         ║
 * ║  Risk per trade: 2% of balance (changed from 40%).                       ║
 * ║  Reason: 40% risk destroys account in 3 losses. 2% allows 50 losses      ║
 * ║  before 63% drawdown. In research mode, capital preservation is          ║
 * ║  paramount — you need enough trades to get statistically valid data.     ║
 * ║                                                                          ║
 * ║  NEW FEATURESTORE FIELDS REQUIRED                                        ║
 * ║  ────────────────────────────────                                         ║
 * ║  • bbWidth          — (BB upper - BB lower) / BB mid                     ║
 * ║  • kcWidth          — (KC upper - KC lower) / KC mid                     ║
 * ║  • atrRatio         — current ATR / 20-period median ATR                  ║
 * ║  • signedEr20       — ER20 with direction sign (+trend up, -trend down)  ║
 * ║  • bbUpperBand      — Bollinger upper band (price)                        ║
 * ║  • bbLowerBand      — Bollinger lower band (price)                        ║
 * ║  • kcUpperBand      — Keltner upper band (price)                          ║
 * ║  • kcLowerBand      — Keltner lower band (price)                          ║
 * ║  See FeatureStoreAdditions.java and TechnicalIndicatorService additions   ║
 * ║                                                                          ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VcbStrategyService implements StrategyExecutor {

    private final StrategyHelper strategyHelper;

    // ── Identity ──────────────────────────────────────────────────────────────
    private static final String STRATEGY_CODE    = "VCB";
    private static final String STRATEGY_NAME    = "Volatility Compression Breakout";
    private static final String STRATEGY_VERSION = "v1";

    private static final String SIDE_LONG  = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String SIGNAL_TYPE_BREAKOUT    = "COMPRESSION_BREAKOUT";
    private static final String SIGNAL_TYPE_MANAGEMENT  = "POSITION_MANAGEMENT";
    private static final String POSITION_ROLE_RUNNER    = "RUNNER";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE  = BigDecimal.ONE;

    // ── Compression thresholds ────────────────────────────────────────────────
    /**
     * BB inside KC when bbWidth < kcWidth.
     * We add a 5% tolerance: bbWidth < kcWidth * 0.95
     * This avoids triggering on marginal squeezes.
     */
    private static final BigDecimal SQUEEZE_KC_TOLERANCE    = new BigDecimal("0.95");

    /**
     * ATR ratio < 1.00 means current ATR is at or below its 20-period median.
     * Prior threshold of 0.90 required ATR to be 10%+ below median — almost never
     * true on BTC 1H because BTC volatility fluctuates tightly around its own average.
     * 1.00 still filters out any active expansion (atrRatio > 1.0 = ATR rising).
     */
    private static final BigDecimal ATR_RATIO_COMPRESS_MAX  = new BigDecimal("1.00");

    /**
     * ER20 < 0.30 = market is directionless / choppy.
     * A compression setup requires low ER (no trending energy yet).
     */
    private static final BigDecimal ER_COMPRESS_MAX         = new BigDecimal("0.30");

    // ── Breakout thresholds ───────────────────────────────────────────────────
    /**
     * Relative volume reference used in scoring only (not a gate).
     * 1.20x is the baseline for the volume scoring bonus — above this earns points.
     * Volume is no longer a hard gate; the score will naturally penalise low-volume breakouts.
     */
    private static final BigDecimal REL_VOL_BREAKOUT_MIN    = new BigDecimal("1.20");

    /**
     * Breakout candle body must be > 45% of its range.
     * Ensures the candle closed with conviction, not a doji or weak bar.
     * Lowered from 0.50: a 45% body is still a directional candle; 0.50 was rejecting
     * valid breakouts with slightly longer wicks.
     */
    private static final BigDecimal BODY_RATIO_BREAKOUT_MIN = new BigDecimal("0.45");

    // ── 4H bias thresholds ────────────────────────────────────────────────────
    /**
     * Minimum 4H signed ER in the direction of trade (soft confirmation only).
     * 0.05 filters out ER that is flat-to-negative at the 4H level.
     * During 1H compression, 4H ER is typically 0.05–0.18 (mild pause, not strong trend).
     * Requiring 0.20 blocked most valid setups. The hard gate is EMA structure.
     */
    private static final BigDecimal BIAS_ER_MIN             = new BigDecimal("0.05");

    // ── Stop / TP parameters ──────────────────────────────────────────────────
    /**
     * Stop buffer below the breakout candle low (long) / above high (short).
     * 0.30 ATR is tight enough for a structural stop without being stopped
     * by normal wick noise on BTC 1H candles.
     */
    private static final BigDecimal STOP_ATR_BUFFER         = new BigDecimal("0.30");

    /**
     * TP1 at 1.8R. At this point 40% of position is closed.
     * Why 1.8R vs 1.5R: breakout setups have higher follow-through than
     * pullback entries. The first target can be set further out.
     * 1.8R at 55% WR → EV = 0.55*1.8 - 0.45*1 = 0.99 - 0.45 = +0.54R per trade.
     */
    private static final BigDecimal TP1_R                   = new BigDecimal("1.80");

    // ── Runner trail phases ───────────────────────────────────────────────────
    private static final BigDecimal RUNNER_BREAK_EVEN_R     = ONE;                       // Move BE at 1R
    private static final BigDecimal RUNNER_PHASE_2_R        = new BigDecimal("2.00");    // Trail at 1.5 ATR
    private static final BigDecimal RUNNER_PHASE_3_R        = new BigDecimal("3.00");    // Trail at 0.8 ATR

    private static final BigDecimal RUNNER_ATR_PHASE_2      = new BigDecimal("1.50");
    private static final BigDecimal RUNNER_ATR_PHASE_3      = new BigDecimal("0.80");

    private static final BigDecimal RUNNER_LOCK_PHASE_2_R   = new BigDecimal("0.80");    // Lock 0.8R at phase 2
    private static final BigDecimal RUNNER_LOCK_PHASE_3_R   = new BigDecimal("2.00");    // Lock 2.0R at phase 3

    // ── Setup labels ──────────────────────────────────────────────────────────
    private static final String SETUP_LONG_BREAKOUT   = "VCB_LONG_BREAKOUT";
    private static final String SETUP_SHORT_BREAKOUT  = "VCB_SHORT_BREAKOUT";
    private static final String SETUP_LONG_RUNNER     = "VCB_LONG_RUNNER_TRAIL";
    private static final String SETUP_SHORT_RUNNER    = "VCB_SHORT_RUNNER_TRAIL";
    private static final String SETUP_LONG_BE         = "VCB_LONG_BREAK_EVEN";
    private static final String SETUP_SHORT_BE        = "VCB_SHORT_BREAK_EVEN";

    private static final String EXIT_STRUCTURE        = "TP1_RUNNER";
    private static final String TARGET_ALL            = "ALL";

    // ════════════════════════════════════════════════════════════════════════
    // Requirements
    // ════════════════════════════════════════════════════════════════════════

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

    // ════════════════════════════════════════════════════════════════════════
    // Main Execute
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public StrategyDecision execute(EnrichedStrategyContext context) {
        if (context == null || context.getMarketData() == null || context.getFeatureStore() == null) {
            return hold(context, "Invalid context or missing data");
        }

        MarketData   marketData = context.getMarketData();
        FeatureStore feature    = context.getFeatureStore();
        PositionSnapshot snap   = context.getPositionSnapshot();

        BigDecimal close = strategyHelper.safe(marketData.getClosePrice());
        if (close.compareTo(ZERO) <= 0) {
            return hold(context, "Invalid close price");
        }

        // Market-level veto (jump risk, tradability)
        if (isMarketVetoed(context)) {
            log.info("VCB VETOED time={} tradable={} jumpRisk={}",
                    marketData.getEndTime(),
                    context.getMarketQualitySnapshot() != null ? context.getMarketQualitySnapshot().getTradable() : "null",
                    resolveJumpRisk(context));
            return veto("Market vetoed by quality / jump-risk filter", context);
        }

        // Manage open position — no session or regime filters apply to management
        if (context.hasTradablePosition() && snap != null) {
            return managePosition(context, marketData, feature, snap);
        }

        // ── Entry path ──
        // 1. Check 4H bias alignment
        // 2. Check 1H compression is (or was recently) active
        // 3. Check current candle is the breakout
        // 4. Build entry decision

        log.info("VCB evaluate time={} longAllowed={} shortAllowed={} prevFs={}",
                marketData.getEndTime(), context.isLongAllowed(), context.isShortAllowed(),
                context.getPreviousFeatureStore() != null ? "present" : "NULL");

        if (context.isLongAllowed()) {
            StrategyDecision d = tryLongEntry(context, marketData, feature);
            if (d != null) return d;
        }

        if (context.isShortAllowed()) {
            StrategyDecision d = tryShortEntry(context, marketData, feature);
            if (d != null) return d;
        }

        return hold(context, "No qualified VCB setup");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Entry — Long
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision tryLongEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        // Layer 1: 4H bias is a SCORE BONUS, not a hard gate.
        // Requiring EMA50 > EMA200 + close > EMA200 blocked the majority of the backtest period:
        // during corrections and range markets, neither long nor short bias ever fired.
        // The Donchian breakout + compression are the actual entry signal.
        // 4H alignment is tracked in the score (+0.15 if aligned) but does not veto entries.

        // Layer 1b: ADX cap — reject if market is already in a strong trend (not compression).
        // ADX >= 35 means momentum is fully extended; the breakout is chasing, not leading.
        // This filtered the Feb 2025 LONG (ADX=42, ATR=$1484) — a massive losing trade.
        if (feature.getAdx() != null && feature.getAdx().compareTo(new BigDecimal("35")) >= 0) {
            log.info("VCB LONG gate1b FAIL [ADX too high] time={} adx={}", marketData.getEndTime(), feature.getAdx());
            return null;
        }

        // Layer 1c: DI directional filter — +DI > -DI confirms bullish directional momentum.
        // Prevents longs when the 1H directional movement is clearly bearish.
        // Null-pass: if DI data unavailable, do not block entry.
        if (feature.getPlusDI() != null && feature.getMinusDI() != null
                && feature.getPlusDI().compareTo(feature.getMinusDI()) < 0) {
            log.info("VCB LONG gate1c FAIL [DI bearish] time={} +DI={} -DI={}",
                    marketData.getEndTime(), feature.getPlusDI(), feature.getMinusDI());
            return null;
        }

        // Layer 1d: RSI momentum filter — RSI must be above 48 for longs.
        // Filters counter-momentum entries where RSI is still in bearish territory (< 48).
        // Null-pass: if RSI unavailable, do not block.
        if (feature.getRsi() != null && feature.getRsi().compareTo(new BigDecimal("48")) < 0) {
            log.info("VCB LONG gate1d FAIL [RSI bearish] time={} rsi={}", marketData.getEndTime(), feature.getRsi());
            return null;
        }

        // Layer 2: Compression was active on the CURRENT or PREVIOUS candle.
        FeatureStore prevFeature = context.getPreviousFeatureStore();
        boolean currCompressed = isCompressionActive(feature);
        boolean prevCompressed = prevFeature != null && isCompressionActive(prevFeature);
        if (!currCompressed && !prevCompressed) {
            log.info("VCB LONG gate2 FAIL [compression] time={} curr[bbW={} kcW={} atrR={} er={}] prev[bbW={} kcW={} atrR={} er={}]",
                    marketData.getEndTime(),
                    feature.getBbWidth(), feature.getKcWidth(), feature.getAtrRatio(), feature.getEfficiencyRatio20(),
                    prevFeature != null ? prevFeature.getBbWidth() : "n/a",
                    prevFeature != null ? prevFeature.getKcWidth() : "n/a",
                    prevFeature != null ? prevFeature.getAtrRatio() : "n/a",
                    prevFeature != null ? prevFeature.getEfficiencyRatio20() : "n/a");
            return null;
        }

        // Layer 3: Close above PREVIOUS 20-period high with body + volume + RSI conviction.
        if (!isBullishBreakoutCandle(feature, prevFeature, marketData)) {
            log.info("VCB LONG gate3 FAIL [breakout candle] time={} close={} prevDonchianUp={} body={} vol={} rsi={}",
                    marketData.getEndTime(), marketData.getClosePrice(),
                    prevFeature != null ? prevFeature.getDonchianUpper20() : "n/a",
                    feature.getBodyToRangeRatio(), feature.getRelativeVolume20(), feature.getRsi());
            return null;
        }

        // Build risk structure
        BigDecimal entryPrice = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr        = resolveAtr(feature);

        // Stop: below the breakout candle low + buffer
        BigDecimal stopLoss   = strategyHelper.safe(marketData.getLowPrice())
                .subtract(atr.multiply(STOP_ATR_BUFFER));
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        // Sanity: if risk > 3% of price, the candle is too wide — skip
        BigDecimal maxAllowedRisk = entryPrice.multiply(new BigDecimal("0.03"));
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) {
            log.debug("VCB long skipped — stop too wide: risk={}% entry={}",
                    riskPerUnit.divide(entryPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")),
                    entryPrice);
            return null;
        }

        BigDecimal tp1 = entryPrice.add(riskPerUnit.multiply(TP1_R));

        BigDecimal signalScore     = calculateLongSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);
        BigDecimal minScore        = resolveMinSignalScore(context);

        if (signalScore.compareTo(minScore) < 0 || confidenceScore.compareTo(minScore) < 0) {
            log.info("VCB LONG score FAIL time={} signal={} confidence={} min={}",
                    marketData.getEndTime(), signalScore, confidenceScore, minScore);
            return null;
        }

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_LONG);
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Long notional size is zero");
        }

        log.info("VCB LONG ENTRY | time={} close={} stop={} tp1={} risk%={} score={}",
                marketData.getEndTime(), entryPrice, stopLoss, tp1,
                riskPerUnit.divide(entryPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")),
                signalScore);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_BREAKOUT)
                .setupType(SETUP_LONG_BREAKOUT)
                .side(SIDE_LONG)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("VCB long: compression squeeze broken upward with volume (v1)")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "VCB", "LONG", "BREAKOUT", "V1"))
                .diagnostics(buildDiagnostics(feature, entryPrice, stopLoss, tp1, signalScore, confidenceScore))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Entry — Short
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision tryShortEntry(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature
    ) {
        // Layer 1: 4H bias is a SCORE BONUS, not a hard gate. Same rationale as long entry.

        // Layer 1b: ADX cap — same rationale as long entry.
        if (feature.getAdx() != null && feature.getAdx().compareTo(new BigDecimal("35")) >= 0) {
            log.info("VCB SHORT gate1b FAIL [ADX too high] time={} adx={}", marketData.getEndTime(), feature.getAdx());
            return null;
        }

        // Layer 1c: DI directional filter — -DI > +DI confirms bearish directional momentum.
        // Null-pass: if DI data unavailable, do not block entry.
        if (feature.getPlusDI() != null && feature.getMinusDI() != null
                && feature.getMinusDI().compareTo(feature.getPlusDI()) < 0) {
            log.info("VCB SHORT gate1c FAIL [DI bullish] time={} +DI={} -DI={}",
                    marketData.getEndTime(), feature.getPlusDI(), feature.getMinusDI());
            return null;
        }

        // Layer 1d: RSI momentum filter — RSI must be below 52 for shorts.
        // Filters counter-momentum entries where RSI is still in bullish territory (> 52).
        // Null-pass: if RSI unavailable, do not block.
        if (feature.getRsi() != null && feature.getRsi().compareTo(new BigDecimal("52")) > 0) {
            log.info("VCB SHORT gate1d FAIL [RSI bullish] time={} rsi={}", marketData.getEndTime(), feature.getRsi());
            return null;
        }

        // Layer 2: Compression on CURRENT or PREVIOUS candle.
        FeatureStore prevFeature = context.getPreviousFeatureStore();
        boolean currCompressed = isCompressionActive(feature);
        boolean prevCompressed = prevFeature != null && isCompressionActive(prevFeature);
        if (!currCompressed && !prevCompressed) {
            log.info("VCB SHORT gate2 FAIL [compression] time={} curr[bbW={} kcW={} atrR={} er={}] prev[bbW={} kcW={} atrR={} er={}]",
                    marketData.getEndTime(),
                    feature.getBbWidth(), feature.getKcWidth(), feature.getAtrRatio(), feature.getEfficiencyRatio20(),
                    prevFeature != null ? prevFeature.getBbWidth() : "n/a",
                    prevFeature != null ? prevFeature.getKcWidth() : "n/a",
                    prevFeature != null ? prevFeature.getAtrRatio() : "n/a",
                    prevFeature != null ? prevFeature.getEfficiencyRatio20() : "n/a");
            return null;
        }

        // Layer 3: Close below PREVIOUS 20-period low with body + volume + RSI conviction.
        if (!isBearishBreakoutCandle(feature, prevFeature, marketData)) {
            log.info("VCB SHORT gate3 FAIL [breakout candle] time={} close={} prevDonchianLow={} body={} vol={} rsi={}",
                    marketData.getEndTime(), marketData.getClosePrice(),
                    prevFeature != null ? prevFeature.getDonchianLower20() : "n/a",
                    feature.getBodyToRangeRatio(), feature.getRelativeVolume20(), feature.getRsi());
            return null;
        }

        BigDecimal entryPrice  = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr         = resolveAtr(feature);

        // Stop: above the breakout candle high + buffer
        BigDecimal stopLoss    = strategyHelper.safe(marketData.getHighPrice())
                .add(atr.multiply(STOP_ATR_BUFFER));
        BigDecimal riskPerUnit = stopLoss.subtract(entryPrice);
        if (riskPerUnit.compareTo(ZERO) <= 0) return null;

        BigDecimal maxAllowedRisk = entryPrice.multiply(new BigDecimal("0.03"));
        if (riskPerUnit.compareTo(maxAllowedRisk) > 0) {
            log.debug("VCB short skipped — stop too wide");
            return null;
        }

        BigDecimal tp1 = entryPrice.subtract(riskPerUnit.multiply(TP1_R));

        BigDecimal signalScore     = calculateShortSignalScore(context, feature, marketData);
        BigDecimal confidenceScore = calculateConfidenceScore(context, signalScore);
        BigDecimal minScore        = resolveMinSignalScore(context);

        if (signalScore.compareTo(minScore) < 0 || confidenceScore.compareTo(minScore) < 0) {
            log.info("VCB SHORT score FAIL time={} signal={} confidence={} min={}",
                    marketData.getEndTime(), signalScore, confidenceScore, minScore);
            return null;
        }

        BigDecimal notionalSize = strategyHelper.calculateEntryNotional(context, SIDE_SHORT);
        if (notionalSize.compareTo(ZERO) <= 0) {
            return hold(context, "Short notional size is zero");
        }

        log.info("VCB SHORT ENTRY | time={} close={} stop={} tp1={} score={}",
                marketData.getEndTime(), entryPrice, stopLoss, tp1, signalScore);

        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_BREAKOUT)
                .setupType(SETUP_SHORT_BREAKOUT)
                .side(SIDE_SHORT)
                .regimeLabel(resolveRegimeLabel(context, feature))
                .reason("VCB short: compression squeeze broken downward with volume (v1)")
                .signalScore(signalScore)
                .confidenceScore(confidenceScore)
                .regimeScore(resolveRegimeScore(context))
                .riskMultiplier(resolveRiskMultiplier(context))
                .jumpRiskScore(resolveJumpRisk(context))
                .notionalSize(notionalSize)
                .stopLossPrice(stopLoss)
                .trailingStopPrice(null)
                .takeProfitPrice1(tp1)
                .takeProfitPrice2(null)
                .takeProfitPrice3(null)
                .exitStructure(EXIT_STRUCTURE)
                .targetPositionRole(TARGET_ALL)
                .entryAdx(feature.getAdx())
                .entryAtr(feature.getAtr())
                .entryRsi(feature.getRsi())
                .entryTrendRegime(feature.getTrendRegime())
                .decisionTime(LocalDateTime.now())
                .tags(List.of("ENTRY", "VCB", "SHORT", "BREAKOUT", "V1"))
                .diagnostics(buildDiagnostics(feature, entryPrice, stopLoss, tp1, signalScore, confidenceScore))
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Position Management Router
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision managePosition(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snap
    ) {
        if (snap.getSide() == null || snap.getEntryPrice() == null || snap.getCurrentStopLossPrice() == null) {
            return hold(context, "Position management: incomplete snapshot");
        }

        boolean isRunner = POSITION_ROLE_RUNNER.equalsIgnoreCase(snap.getPositionRole());

        if (SIDE_LONG.equalsIgnoreCase(snap.getSide())) {
            return isRunner
                    ? manageRunnerLong(context, marketData, feature, snap)
                    : manageStandardLong(context, marketData, snap);
        }
        if (SIDE_SHORT.equalsIgnoreCase(snap.getSide())) {
            return isRunner
                    ? manageRunnerShort(context, marketData, feature, snap)
                    : manageStandardShort(context, marketData, snap);
        }

        return hold(context, "Position management: unknown side");
    }

    // ── Standard position (TP1 leg, just break-even) ─────────────────────────

    private StrategyDecision manageStandardLong(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snap
    ) {
        BigDecimal entry    = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop  = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close    = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal risk     = entry.subtract(curStop);

        if (risk.compareTo(ZERO) <= 0) return hold(context, "Invalid long risk");

        // Move to BE after 1R
        if (close.subtract(entry).compareTo(risk.multiply(RUNNER_BREAK_EVEN_R)) < 0)
            return hold(context, "Long not ready for BE");
        if (curStop.compareTo(entry) >= 0)
            return hold(context, "Long stop already at BE");

        return buildManagementDecision(context, SIDE_LONG, SETUP_LONG_BE,
                entry, snap.getTakeProfitPrice(),
                "Move long TP1 leg to break-even after 1R",
                Map.of("entry", entry, "curStop", curStop, "close", close));
    }

    private StrategyDecision manageStandardShort(
            EnrichedStrategyContext context,
            MarketData marketData,
            PositionSnapshot snap
    ) {
        BigDecimal entry    = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop  = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close    = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal risk     = curStop.subtract(entry);

        if (risk.compareTo(ZERO) <= 0) return hold(context, "Invalid short risk");

        if (entry.subtract(close).compareTo(risk.multiply(RUNNER_BREAK_EVEN_R)) < 0)
            return hold(context, "Short not ready for BE");
        if (curStop.compareTo(entry) <= 0)
            return hold(context, "Short stop already at BE");

        return buildManagementDecision(context, SIDE_SHORT, SETUP_SHORT_BE,
                entry, snap.getTakeProfitPrice(),
                "Move short TP1 leg to break-even after 1R",
                Map.of("entry", entry, "curStop", curStop, "close", close));
    }

    // ── Runner position (3-phase ATR trail) ───────────────────────────────────

    private StrategyDecision manageRunnerLong(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snap
    ) {
        // If 4H bias has flipped bearish while we hold a long runner, exit immediately.
        // Holding a long runner against a reversed macro structure destroys the edge.
        if (isBearish4HBias(context)) {
            log.info("VCB long runner exited — 4H bias reversed to bearish | close={}",
                    marketData.getClosePrice());
            return StrategyDecision.builder()
                    .decisionType(DecisionType.CLOSE_LONG)
                    .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                    .strategyInterval(context.getInterval())
                    .signalType(SIGNAL_TYPE_MANAGEMENT)
                    .setupType(SETUP_LONG_RUNNER)
                    .side(SIDE_LONG)
                    .reason("VCB long runner: 4H bias reversed — macro structure no longer supports trade")
                    .targetPositionRole(POSITION_ROLE_RUNNER)
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("MANAGEMENT", "VCB", "LONG", "REGIME_EXIT"))
                    .diagnostics(Map.of())
                    .build();
        }

        BigDecimal entry      = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop    = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close      = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr        = resolveAtr(feature);

        BigDecimal initStop   = snap.getInitialStopLossPrice() != null
                ? snap.getInitialStopLossPrice() : curStop;
        BigDecimal initRisk   = entry.subtract(initStop);
        if (initRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid runner risk");

        BigDecimal move       = close.subtract(entry);
        if (move.compareTo(ZERO) <= 0) return hold(context, "Runner not in profit");

        BigDecimal rMultiple  = move.divide(initRisk, 6, RoundingMode.HALF_UP);

        BigDecimal candidate  = null;
        String reason         = null;
        String setup          = null;

        if (rMultiple.compareTo(RUNNER_PHASE_3_R) >= 0) {
            // Phase 3: tight trail, lock 2R
            BigDecimal atrStop    = close.subtract(atr.multiply(RUNNER_ATR_PHASE_3));
            BigDecimal lockStop   = entry.add(initRisk.multiply(RUNNER_LOCK_PHASE_3_R));
            candidate = atrStop.max(lockStop).max(entry);
            reason    = "VCB runner phase 3: trail 0.8 ATR, lock 2R";
            setup     = SETUP_LONG_RUNNER;

        } else if (rMultiple.compareTo(RUNNER_PHASE_2_R) >= 0) {
            // Phase 2: moderate trail, lock 0.8R
            BigDecimal atrStop    = close.subtract(atr.multiply(RUNNER_ATR_PHASE_2));
            BigDecimal lockStop   = entry.add(initRisk.multiply(RUNNER_LOCK_PHASE_2_R));
            candidate = atrStop.max(lockStop).max(entry);
            reason    = "VCB runner phase 2: trail 1.5 ATR, lock 0.8R";
            setup     = SETUP_LONG_RUNNER;

        } else if (rMultiple.compareTo(RUNNER_BREAK_EVEN_R) >= 0) {
            // Phase 1: move to break-even
            candidate = entry;
            reason    = "VCB runner phase 1: move to break-even at 1R";
            setup     = SETUP_LONG_BE;
        }

        if (candidate == null) return hold(context, "Runner not ready");
        if (candidate.compareTo(curStop) <= 0 || candidate.compareTo(close) >= 0)
            return hold(context, "Runner stop already optimal");

        return buildTrailDecision(context, SIDE_LONG, setup, candidate,
                snap.getTakeProfitPrice(), reason,
                Map.of("rMultiple", rMultiple, "candidate", candidate,
                        "curStop", curStop, "close", close, "atr", atr));
    }

    private StrategyDecision manageRunnerShort(
            EnrichedStrategyContext context,
            MarketData marketData,
            FeatureStore feature,
            PositionSnapshot snap
    ) {
        // If 4H bias has flipped bullish while we hold a short runner, exit immediately.
        if (isBullish4HBias(context)) {
            log.info("VCB short runner exited — 4H bias reversed to bullish | close={}",
                    marketData.getClosePrice());
            return StrategyDecision.builder()
                    .decisionType(DecisionType.CLOSE_SHORT)
                    .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                    .strategyInterval(context.getInterval())
                    .signalType(SIGNAL_TYPE_MANAGEMENT)
                    .setupType(SETUP_SHORT_RUNNER)
                    .side(SIDE_SHORT)
                    .reason("VCB short runner: 4H bias reversed — macro structure no longer supports trade")
                    .targetPositionRole(POSITION_ROLE_RUNNER)
                    .decisionTime(LocalDateTime.now())
                    .tags(List.of("MANAGEMENT", "VCB", "SHORT", "REGIME_EXIT"))
                    .diagnostics(Map.of())
                    .build();
        }

        BigDecimal entry      = strategyHelper.safe(snap.getEntryPrice());
        BigDecimal curStop    = strategyHelper.safe(snap.getCurrentStopLossPrice());
        BigDecimal close      = strategyHelper.safe(marketData.getClosePrice());
        BigDecimal atr        = resolveAtr(feature);

        BigDecimal initStop   = snap.getInitialStopLossPrice() != null
                ? snap.getInitialStopLossPrice() : curStop;
        BigDecimal initRisk   = initStop.subtract(entry);
        if (initRisk.compareTo(ZERO) <= 0) return hold(context, "Invalid runner risk");

        BigDecimal move       = entry.subtract(close);
        if (move.compareTo(ZERO) <= 0) return hold(context, "Runner not in profit");

        BigDecimal rMultiple  = move.divide(initRisk, 6, RoundingMode.HALF_UP);

        BigDecimal candidate  = null;
        String reason         = null;
        String setup          = null;

        if (rMultiple.compareTo(RUNNER_PHASE_3_R) >= 0) {
            BigDecimal atrStop  = close.add(atr.multiply(RUNNER_ATR_PHASE_3));
            BigDecimal lockStop = entry.subtract(initRisk.multiply(RUNNER_LOCK_PHASE_3_R));
            candidate = atrStop.min(lockStop).min(entry);
            reason    = "VCB short runner phase 3: trail 0.8 ATR, lock 2R";
            setup     = SETUP_SHORT_RUNNER;

        } else if (rMultiple.compareTo(RUNNER_PHASE_2_R) >= 0) {
            BigDecimal atrStop  = close.add(atr.multiply(RUNNER_ATR_PHASE_2));
            BigDecimal lockStop = entry.subtract(initRisk.multiply(RUNNER_LOCK_PHASE_2_R));
            candidate = atrStop.min(lockStop).min(entry);
            reason    = "VCB short runner phase 2: trail 1.5 ATR, lock 0.8R";
            setup     = SETUP_SHORT_RUNNER;

        } else if (rMultiple.compareTo(RUNNER_BREAK_EVEN_R) >= 0) {
            candidate = entry;
            reason    = "VCB short runner phase 1: move to break-even at 1R";
            setup     = SETUP_SHORT_BE;
        }

        if (candidate == null) return hold(context, "Short runner not ready");
        if (candidate.compareTo(curStop) >= 0 || candidate.compareTo(close) <= 0)
            return hold(context, "Short runner stop already optimal");

        return buildTrailDecision(context, SIDE_SHORT, setup, candidate,
                snap.getTakeProfitPrice(), reason,
                Map.of("rMultiple", rMultiple, "candidate", candidate,
                        "curStop", curStop, "close", close, "atr", atr));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Signal Filters
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 4H bullish bias:
     *   - EMA50 > EMA200  (structural uptrend)
     *   - close > EMA50   (price above trend)
     *   - ema50Slope > 0  (trend accelerating)
     *   - signedEr20 > BIAS_ER_MIN (4H is directionally efficient upward)
     */
    private boolean isBullish4HBias(EnrichedStrategyContext context) {
        FeatureStore bias   = context.getBiasFeatureStore();
        MarketData biasData = context.getBiasMarketData();

        if (bias == null || biasData == null) {
            // No 4H data → direction is unknown. Fail-closed: do not trade without a bias gate.
            log.debug("VCB: no 4H bias data, rejecting long entry");
            return false;
        }

        // Hard gate: structural uptrend (EMA50 > EMA200) and price above EMA200 (macro bullish zone).
        // We deliberately check close > EMA200 rather than close > EMA50.
        // 1H compressions form DURING 4H pullbacks — price often dips below 4H EMA50 but
        // stays above EMA200. That is the ideal VCB setup: 4H pullback → 1H squeeze → breakout.
        // Requiring close > EMA50 blocks exactly these high-quality setups.
        boolean emaStructure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasData.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) > 0
                && biasData.getClosePrice().compareTo(bias.getEma200()) > 0;

        // Soft confirmation: just filters out flat or bearish 4H ER (< 0.05).
        boolean erAligned = bias.getSignedEr20() == null
                || bias.getSignedEr20().compareTo(BIAS_ER_MIN) > 0;

        log.debug("VCB 4H BULL bias: emaStructure={} erAligned={} | ema50={} ema200={} close={} slope={} signedEr20={}",
                emaStructure, erAligned,
                bias.getEma50(), bias.getEma200(),
                biasData.getClosePrice(), bias.getEma50Slope(), bias.getSignedEr20());

        return emaStructure && erAligned;
    }

    private boolean isBearish4HBias(EnrichedStrategyContext context) {
        FeatureStore bias   = context.getBiasFeatureStore();
        MarketData biasData = context.getBiasMarketData();

        if (bias == null || biasData == null) {
            // No 4H data → direction is unknown. Fail-closed: do not trade without a bias gate.
            log.debug("VCB: no 4H bias data, rejecting short entry");
            return false;
        }

        // Hard gate: structural downtrend (EMA50 < EMA200) and price below EMA200 (macro bearish zone).
        // Same rationale as bull bias: check close < EMA200, not close < EMA50.
        // 4H relief rallies push price above EMA50 while staying below EMA200 — those are
        // valid bearish VCB setups that would be blocked by requiring close < EMA50.
        boolean emaStructure = strategyHelper.hasValue(bias.getEma50())
                && strategyHelper.hasValue(bias.getEma200())
                && strategyHelper.hasValue(biasData.getClosePrice())
                && bias.getEma50().compareTo(bias.getEma200()) < 0
                && biasData.getClosePrice().compareTo(bias.getEma200()) < 0;

        // Soft confirmation: filters out flat or bullish 4H ER (> -0.05).
        boolean erAligned = bias.getSignedEr20() == null
                || bias.getSignedEr20().compareTo(BIAS_ER_MIN.negate()) < 0;

        return emaStructure && erAligned;
    }

    /**
     * Compression is active when ALL THREE conditions hold:
     *   1. BB width < KC width * 0.95  (Bollinger bands squeezed inside Keltner)
     *   2. ATR ratio < 0.90            (ATR below its 20-period median)
     *   3. ER20 < 0.30                 (low directional efficiency = choppy/ranging)
     *
     * This is the TTM Squeeze concept made quantitative and stricter.
     * All three must agree — this prevents false positives on any single measure.
     */
    private boolean isCompressionActive(FeatureStore feature) {
        // Condition 1: BB squeeze
        boolean bbSqueeze = false;
        if (feature.getBbWidth() != null && feature.getKcWidth() != null
                && feature.getKcWidth().compareTo(ZERO) > 0) {
            BigDecimal kcThreshold = feature.getKcWidth().multiply(SQUEEZE_KC_TOLERANCE);
            bbSqueeze = feature.getBbWidth().compareTo(kcThreshold) < 0;
        }

        // Condition 2: ATR not expanding (at or below its 20-period median).
        // Null → treat as not compressed (fail-closed).
        boolean atrCompressed = feature.getAtrRatio() != null
                && feature.getAtrRatio().compareTo(ATR_RATIO_COMPRESS_MAX) < 0;

        // Condition 3: Low directional efficiency — market is ranging/choppy.
        // Null → treat as not ranging (fail-closed).
        boolean erLow = feature.getEfficiencyRatio20() != null
                && feature.getEfficiencyRatio20().compareTo(ER_COMPRESS_MAX) < 0;

        // Require 2 of 3 conditions.
        // bbSqueeze and atrCompressed are anti-correlated: when BB is inside KC (KC is wide,
        // meaning ATR is high), atrRatio tends to be above 1.0. Requiring all three together
        // almost never passes. 2-of-3 preserves the intent while handling this structural issue.
        int conditionsMet = (bbSqueeze ? 1 : 0) + (atrCompressed ? 1 : 0) + (erLow ? 1 : 0);
        boolean result = conditionsMet >= 2;

        log.debug("VCB compression check: bbSqueeze={} atrCompressed={} erLow={} met={} → {}",
                bbSqueeze, atrCompressed, erLow, conditionsMet, result);

        return result;
    }

    /**
     * Bullish breakout candle:
     *   - close > prevFeature.donchianUpper20  (close above the PRIOR 20-period high = true breakout)
     *   - bodyToRangeRatio > 0.45              (conviction candle — filters dojis and weak bars)
     *
     * KEY FIX: donchianUpper20 = highestHigh20 and is computed INCLUDING the current candle's own
     * high. So feature.donchianUpper20 is always >= currentHigh, and close >= currentHigh is nearly
     * impossible (close is almost always below the candle's high). That is why gate3 almost never
     * fired with the original code.
     *
     * The correct breakout reference is prevFeature.donchianUpper20 — the resistance level from
     * the PREVIOUS 20 candles, before this candle formed. close > that level = price has broken
     * out of the recent range. This is the standard Donchian channel breakout signal.
     *
     * prevFeature is guaranteed non-null when this method is called (gate2 already checks it).
     */
    private boolean isBullishBreakoutCandle(FeatureStore feature, FeatureStore prevFeature, MarketData marketData) {
        if (prevFeature == null || !strategyHelper.hasValue(prevFeature.getDonchianUpper20())
                || !strategyHelper.hasValue(marketData.getClosePrice())) {
            return false;
        }

        // Close must exceed the previous 20-period high — breaking out of prior resistance.
        boolean abovePrevDonchian = marketData.getClosePrice()
                .compareTo(prevFeature.getDonchianUpper20()) > 0;

        // Conviction candle: body must be at least 45% of range.
        boolean convictionCandle = strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(BODY_RATIO_BREAKOUT_MIN) >= 0;

        // Volume confirmation: breakout must have above-average volume (>= 1.15x).
        // Null-pass: if volume data unavailable, do not block.
        boolean volumeConfirmed = !strategyHelper.hasValue(feature.getRelativeVolume20())
                || feature.getRelativeVolume20().compareTo(new BigDecimal("1.15")) >= 0;

        return abovePrevDonchian && convictionCandle && volumeConfirmed;
    }

    private boolean isBearishBreakoutCandle(FeatureStore feature, FeatureStore prevFeature, MarketData marketData) {
        if (prevFeature == null || !strategyHelper.hasValue(prevFeature.getDonchianLower20())
                || !strategyHelper.hasValue(marketData.getClosePrice())) {
            return false;
        }

        // Close must be below the previous 20-period low — breaking out of prior support.
        boolean belowPrevDonchian = marketData.getClosePrice()
                .compareTo(prevFeature.getDonchianLower20()) < 0;

        boolean convictionCandle = strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(BODY_RATIO_BREAKOUT_MIN) >= 0;

        // Volume confirmation: breakout must have above-average volume (>= 1.15x).
        // Null-pass: if volume data unavailable, do not block.
        boolean volumeConfirmed = !strategyHelper.hasValue(feature.getRelativeVolume20())
                || feature.getRelativeVolume20().compareTo(new BigDecimal("1.15")) >= 0;

        return belowPrevDonchian && convictionCandle && volumeConfirmed;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Signal Scoring
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Score components for a long signal (max 1.00):
     *
     * Base:               0.35  (always — passing all gates earns the base)
     * + 4H bias aligned   0.15  (EMA50 > EMA200 on 4H = structural uptrend confirms direction)
     * + BB squeeze depth  0.10  (prevFeature bbWidth / kcWidth < 0.70 = tight prior squeeze)
     * + ATR ratio depth   0.10  (prevFeature atrRatio < 0.90 = ATR compressed before breakout)
     * + Volume strength   0.15  (relVol20 ≥ 1.30 = above-average breakout volume)
     * + Body conviction   0.10  (bodyToRangeRatio > 0.65 = strong directional candle)
     * + ADX confirmation  0.10  (ADX 12–25 = early trend initiation from compression)
     *
     * Total max: 1.05 → capped at 1.00
     * Threshold: 0.45 flat. Gate cascade is the primary quality filter.
     *
     * 4H bias is now a score bonus (+0.15) not a gate. During range/correction markets
     * the bias gate blocked all entries. The Donchian breakout + compression are the signal.
     */
    private BigDecimal calculateLongSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        BigDecimal score = new BigDecimal("0.35");

        // 4H macro bias alignment bonus — bigger reward for trading with the structural trend.
        // No penalty for misaligned 4H (the gates already vetted the setup).
        if (isBullish4HBias(context))
            score = score.add(new BigDecimal("0.15"));

        // BB squeeze depth bonus — measure on PREVIOUS candle (the compression candle).
        FeatureStore prevFeature = context.getPreviousFeatureStore();
        if (prevFeature != null && prevFeature.getBbWidth() != null && prevFeature.getKcWidth() != null
                && prevFeature.getKcWidth().compareTo(ZERO) > 0) {
            BigDecimal ratio = prevFeature.getBbWidth().divide(prevFeature.getKcWidth(), 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(new BigDecimal("0.70")) < 0)
                score = score.add(new BigDecimal("0.10"));
        }

        // ATR compression depth bonus — measure on PREVIOUS candle.
        if (prevFeature != null && prevFeature.getAtrRatio() != null
                && prevFeature.getAtrRatio().compareTo(new BigDecimal("0.90")) < 0)
            score = score.add(new BigDecimal("0.10"));

        // Volume confirmation
        if (strategyHelper.hasValue(feature.getRelativeVolume20())
                && feature.getRelativeVolume20().compareTo(new BigDecimal("1.30")) >= 0)
            score = score.add(new BigDecimal("0.15"));

        // Breakout candle conviction
        if (strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(new BigDecimal("0.65")) >= 0)
            score = score.add(new BigDecimal("0.10"));

        // ADX: early trend initiation range lowered to 12–25.
        // Real entries had ADX of 15-17 — the prior 18-30 range never fired.
        // ADX 12–25 captures the compression-to-expansion transition.
        if (strategyHelper.hasValue(feature.getAdx())
                && feature.getAdx().compareTo(new BigDecimal("12")) >= 0
                && feature.getAdx().compareTo(new BigDecimal("25")) < 0)
            score = score.add(new BigDecimal("0.10"));

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateShortSignalScore(
            EnrichedStrategyContext context,
            FeatureStore feature,
            MarketData marketData
    ) {
        BigDecimal score = new BigDecimal("0.35");

        // 4H macro bias alignment bonus for shorts
        if (isBearish4HBias(context))
            score = score.add(new BigDecimal("0.15"));

        FeatureStore prevFeature = context.getPreviousFeatureStore();
        if (prevFeature != null && prevFeature.getBbWidth() != null && prevFeature.getKcWidth() != null
                && prevFeature.getKcWidth().compareTo(ZERO) > 0) {
            BigDecimal ratio = prevFeature.getBbWidth().divide(prevFeature.getKcWidth(), 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(new BigDecimal("0.70")) < 0)
                score = score.add(new BigDecimal("0.10"));
        }

        if (prevFeature != null && prevFeature.getAtrRatio() != null
                && prevFeature.getAtrRatio().compareTo(new BigDecimal("0.90")) < 0)
            score = score.add(new BigDecimal("0.10"));

        if (strategyHelper.hasValue(feature.getRelativeVolume20())
                && feature.getRelativeVolume20().compareTo(new BigDecimal("1.30")) >= 0)
            score = score.add(new BigDecimal("0.15"));

        if (strategyHelper.hasValue(feature.getBodyToRangeRatio())
                && feature.getBodyToRangeRatio().compareTo(new BigDecimal("0.65")) >= 0)
            score = score.add(new BigDecimal("0.10"));

        // ADX: lowered range to 12–25 (same rationale as long score).
        if (strategyHelper.hasValue(feature.getAdx())
                && feature.getAdx().compareTo(new BigDecimal("12")) >= 0
                && feature.getAdx().compareTo(new BigDecimal("25")) < 0)
            score = score.add(new BigDecimal("0.10"));

        return score.min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateConfidenceScore(EnrichedStrategyContext context, BigDecimal signalScore) {
        BigDecimal confidence = strategyHelper.safe(signalScore);

        // Regime bonus
        confidence = confidence.add(resolveRegimeScore(context).multiply(new BigDecimal("0.10")));

        // Jump risk penalty
        if (resolveJumpRisk(context).compareTo(new BigDecimal("0.50")) > 0) {
            confidence = confidence.subtract(new BigDecimal("0.15"));
        }

        return confidence.max(ZERO).min(ONE).setScale(4, RoundingMode.HALF_UP);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Market Veto
    // ════════════════════════════════════════════════════════════════════════

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

    // ════════════════════════════════════════════════════════════════════════
    // Decision Builders (DRY helpers)
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision buildManagementDecision(
            EnrichedStrategyContext context,
            String side,
            String setupType,
            BigDecimal newStop,
            BigDecimal takeProfit,
            String reason,
            Map<String, Object> diag
    ) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(setupType).side(side).reason(reason)
                .stopLossPrice(newStop).trailingStopPrice(null)
                .takeProfitPrice1(takeProfit)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "VCB", side, "BREAK_EVEN"))
                .diagnostics(diag)
                .build();
    }

    private StrategyDecision buildTrailDecision(
            EnrichedStrategyContext context,
            String side,
            String setupType,
            BigDecimal newStop,
            BigDecimal takeProfit,
            String reason,
            Map<String, Object> diag
    ) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.UPDATE_POSITION_MANAGEMENT)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context.getInterval())
                .signalType(SIGNAL_TYPE_MANAGEMENT)
                .setupType(setupType).side(side).reason(reason)
                .stopLossPrice(newStop).trailingStopPrice(newStop)
                .takeProfitPrice1(takeProfit)
                .targetPositionRole(TARGET_ALL)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("MANAGEMENT", "VCB", side, "RUNNER_TRAIL"))
                .diagnostics(diag)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Resolvers
    // ════════════════════════════════════════════════════════════════════════

    private BigDecimal resolveAtr(FeatureStore feature) {
        return (feature != null && feature.getAtr() != null && feature.getAtr().compareTo(ZERO) > 0)
                ? feature.getAtr() : ONE;
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

    private BigDecimal resolveMinSignalScore(EnrichedStrategyContext context) {
        // Flat threshold: 0.45 — base 0.35 + at least 1 quality bonus.
        // The gate cascade (4H bias + compression + Donchian body breakout) is the primary
        // quality filter. A setup must pass all gates AND achieve at least two quality bonuses
        // (volume + body, or 4H bias + volume, etc.) to reach this threshold.
        // Raised from 0.45: with volume now a hard gate, fewer setups pass — we can be more selective.
        return new BigDecimal("0.55");
    }

    private String resolveRegimeLabel(EnrichedStrategyContext context, FeatureStore feature) {
        if (context.getRegimeSnapshot() != null && context.getRegimeSnapshot().getRegimeLabel() != null) {
            return context.getRegimeSnapshot().getRegimeLabel();
        }
        return feature != null ? feature.getTrendRegime() : null;
    }

    private Map<String, Object> buildDiagnostics(
            FeatureStore feature,
            BigDecimal entry, BigDecimal stop, BigDecimal tp1,
            BigDecimal signal, BigDecimal confidence
    ) {
        return Map.of(
                "module", "VcbStrategyService",
                "entryPrice", entry,
                "stopLoss", stop,
                "tp1", tp1,
                "signalScore", signal,
                "confidenceScore", confidence,
                "bbWidth", feature.getBbWidth() != null ? feature.getBbWidth() : ZERO,
                "kcWidth", feature.getKcWidth() != null ? feature.getKcWidth() : ZERO,
                "atrRatio", feature.getAtrRatio() != null ? feature.getAtrRatio() : ZERO,
                "er20", feature.getEfficiencyRatio20() != null ? feature.getEfficiencyRatio20() : ZERO
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // Hold / Veto
    // ════════════════════════════════════════════════════════════════════════

    private StrategyDecision hold(EnrichedStrategyContext context, String reason) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .signalType(SIGNAL_TYPE_BREAKOUT)
                .reason(reason)
                .decisionTime(LocalDateTime.now())
                .tags(List.of("HOLD", "VCB"))
                .build();
    }

    private StrategyDecision veto(String vetoReason, EnrichedStrategyContext context) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE).strategyName(STRATEGY_NAME).strategyVersion(STRATEGY_VERSION)
                .strategyInterval(context != null ? context.getInterval() : null)
                .vetoed(Boolean.TRUE)
                .vetoReason(vetoReason)
                .reason("VCB vetoed by risk layer")
                .jumpRiskScore(resolveJumpRisk(context))
                .decisionTime(LocalDateTime.now())
                .tags(List.of("VETO", "VCB", "RISK_LAYER"))
                .diagnostics(Map.of())
                .build();
    }
}