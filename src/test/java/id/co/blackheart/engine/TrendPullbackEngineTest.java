package id.co.blackheart.engine;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.service.strategy.StrategyHelper;
import id.co.blackheart.util.TradeConstant.DecisionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Value-equality helper for BigDecimal — assertEquals uses Object.equals which
// is scale-sensitive (100290.00 ≠ 100290.0000). All BigDecimal comparisons in
// this file go through {@code bd(expected).compareTo(actual) == 0}.

/**
 * Behavior validation for {@link TrendPullbackEngine}. We don't run a
 * field-for-field parity comparison against {@code TrendPullbackStrategyService}
 * the way M2 did for BBR — TPR is a research-grade strategy that pulls params
 * via Spring-injected {@code ResearchParamService} + JSON round-trip, and the
 * test cost of mocking that surface outweighs the value (TPR's defaults move
 * with research iteration; bit-perfect parity isn't actually informative).
 *
 * <p>Instead, we exercise each gate path against curated fixtures and assert
 * the engine produces the expected decision shape. The default tuning is
 * checked-in alongside TPR's, so behavioural drift would surface as a
 * compile-time miss when someone reorders TPR's defaults without updating
 * the engine constants.
 */
class TrendPullbackEngineTest {

    private final StrategyHelper helper = new StrategyHelper();
    private final TrendPullbackEngine engine = new TrendPullbackEngine(helper);

    @Test
    void longEntry_passesAllGates_emitsOpenLongWithCorrectStopAndTp() {
        EnrichedStrategyContext ctx = longEntryContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.OPEN_LONG, d.getDecisionType());
        assertEquals("LONG", d.getSide());
        assertNotNull(d.getNotionalSize(), "LONG must size in USDT (notional)");
        assertNull(d.getPositionSize(), "LONG must not carry positionSize");

        // Stop = low - atr * stopAtrBuffer = 100400 - 200 * 0.55 = 100290
        assertBdEq("100290", d.getStopLossPrice());
        // Risk = 100500 - 100290 = 210; TP1 = entry + 2.0R = 100500 + 420 = 100920
        assertBdEq("100920", d.getTakeProfitPrice1());
        assertEquals("TP1_RUNNER", d.getExitStructure());
        assertEquals("ALL", d.getTargetPositionRole());
        assertTrue(d.getTags().contains("ENTRY"));
    }

    @Test
    void shortEntry_passesAllGates_emitsOpenShortWithBtcQty() {
        EnrichedStrategyContext ctx = shortEntryContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.OPEN_SHORT, d.getDecisionType());
        assertEquals("SHORT", d.getSide());
        assertNotNull(d.getPositionSize(), "SHORT must size in BTC (positionSize)");
        assertNull(d.getNotionalSize(), "SHORT must not carry notionalSize");
        assertEquals("TP1_RUNNER", d.getExitStructure());
    }

    @Test
    void biasMissing_holds() {
        EnrichedStrategyContext ctx = longEntryContext();
        ctx.setBiasFeatureStore(null);
        ctx.setBiasMarketData(null);
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void adxBelowFloor_holds() {
        EnrichedStrategyContext ctx = longEntryContext();
        ctx.getFeatureStore().setAdx(new BigDecimal("20"));   // < 25 floor
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void clvAboveCeiling_holds_rejectsExhaustionClose() {
        EnrichedStrategyContext ctx = longEntryContext();
        ctx.getFeatureStore().setCloseLocationValue(new BigDecimal("0.95")); // > 0.90 ceiling
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void breakEvenShift_movesStopToEntryAtOneR() {
        // LONG TP1 leg, +1.0R favourable => engine moves stop to entry.
        EnrichedStrategyContext ctx = breakEvenContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.UPDATE_POSITION_MANAGEMENT, d.getDecisionType());
        assertEquals("LONG", d.getSide());
        // entry=100000, initStop=99000, init risk=1000, +1R reached at close=101000.
        assertBdEq("100000", d.getStopLossPrice());
        assertEquals("TP1", d.getTargetPositionRole());
        assertTrue(d.getTags().contains("BREAK_EVEN"));
    }

    // ── Risk-based sizing + fallback chain (V2) ──────────────────────────

    /**
     * Risk-based sizing default: spec params {@code {}} → engine reads
     * {@code D_RISK_PER_TRADE_PCT=0.02}, {@code D_MAX_ALLOCATION_PCT=1.00}.
     * Long-entry fixture has stop ~0.21% wide; ideal = 10000 × 0.02 / 0.0021
     * exceeds 100% cap → notional saturates at cashBalance × 1.0 = 10000.
     */
    @Test
    void longEntry_defaultRiskBasedSizing_saturatesAt100pctCap() {
        EnrichedStrategyContext ctx = longEntryContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.OPEN_LONG, d.getDecisionType());
        assertBdEq("10000", d.getNotionalSize());   // cashBalance × 1.0 cap
    }

    /**
     * Fallback chain: when spec sets {@code riskPerTradePct=0} the helper
     * returns ZERO and the engine must fall through to
     * {@code calculateEntryNotional}. With cashBalance=10000 and
     * capitalAllocationPct=50, expected notional = 5000.
     */
    @Test
    void longEntry_riskPerTradeDisabled_fallsBackToLegacyNotional() {
        StrategySpec withDisabledRisk = specWithParams(Map.of(
                "riskPerTradePct", BigDecimal.ZERO));
        EnrichedStrategyContext ctx = longEntryContext();
        StrategyDecision d = engine.evaluate(withDisabledRisk, ctx);

        assertEquals(DecisionType.OPEN_LONG, d.getDecisionType());
        assertBdEq("5000", d.getNotionalSize());   // 10000 × 50% legacy alloc
    }

    /**
     * SHORT fallback: when {@code riskPerTradePct=0} the engine falls back
     * to {@code calculateShortPositionSize}, which returns
     * {@code assetBalance × allocFraction = 0.1 × 0.50 = 0.05} BTC.
     */
    @Test
    void shortEntry_riskPerTradeDisabled_fallsBackToLegacyPositionSize() {
        StrategySpec withDisabledRisk = specWithParams(Map.of(
                "riskPerTradePct", BigDecimal.ZERO));
        EnrichedStrategyContext ctx = shortEntryContext();
        StrategyDecision d = engine.evaluate(withDisabledRisk, ctx);

        assertEquals(DecisionType.OPEN_SHORT, d.getDecisionType());
        assertBdEq("0.05", d.getPositionSize());   // assetBalance × allocFraction
    }

    /**
     * SHORT default risk-based: stop ~0.21% wide; ideal saturates 100% cap
     * at $10,000 USDT notional. Conversion to BTC qty = 10000 / 99700.
     */
    @Test
    void shortEntry_defaultRiskBasedSizing_convertsUsdtToBtcQty() {
        EnrichedStrategyContext ctx = shortEntryContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.OPEN_SHORT, d.getDecisionType());
        // 10000 / 99700 = 0.10030090 (8-dp HALF_UP). Exact match to engine math.
        assertBdEq("0.10030090", d.getPositionSize());
    }

    @Test
    void runnerPhase2Trail_locksAtLeastOneR() {
        // Runner @ +2.0R => phase-2 trail kicks in. Entry=100000 init risk=1000.
        // Lock = entry + 1R = 101000. ATR=200, phase2=2.5*ATR = 500.
        // Trail = close 102000 - 500 = 101500. Candidate = max(trail, lock) = 101500.
        EnrichedStrategyContext ctx = runnerPhase2Context();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.UPDATE_POSITION_MANAGEMENT, d.getDecisionType());
        assertEquals("RUNNER", d.getTargetPositionRole());
        assertBdEq("101500", d.getStopLossPrice());
        assertBdEq("101500", d.getTrailingStopPrice());
        assertTrue(d.getTags().contains("TRAIL"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Scale-insensitive BigDecimal equality. {@code assertEquals} uses
     *  Object.equals which fails on scale mismatch (e.g. {@code 100920.00 ≠
     *  100920.0000}); for price/level comparisons we only care about value. */
    private static void assertBdEq(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but got null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static StrategySpec spec() {
        return specWithParams(new HashMap<>());
    }

    /** Build a TPR spec with explicit param overrides. Used by sizing tests
     *  that need to pin {@code riskPerTradePct} or {@code maxAllocationPct}
     *  to a specific value rather than relying on engine defaults. */
    private static StrategySpec specWithParams(Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>();
        body.put("params", new HashMap<>(params));
        return StrategySpec.builder()
                .strategyCode("TPR")
                .strategyName("TPR")
                .archetype(TrendPullbackEngine.ARCHETYPE)
                .archetypeVersion(TrendPullbackEngine.VERSION)
                .specSchemaVersion(1)
                .params(new HashMap<>(params))
                .body(body)
                .build();
    }

    private static EnrichedStrategyContext longEntryContext() {
        // Pullback that touched EMA20 and reclaimed: low=100400 ≤ ema20+(0.4*atr)=100580.
        // Close=100500 > ema20=100500 ... must be > so use 100501. Adjust below.
        FeatureStore now = new FeatureStore();
        now.setEma20(new BigDecimal("100400"));   // pullback target
        now.setEma50(new BigDecimal("100200"));
        now.setEma200(new BigDecimal("99000"));
        now.setEma50Slope(new BigDecimal("5"));
        now.setAdx(new BigDecimal("30"));
        now.setPlusDI(new BigDecimal("28"));
        now.setMinusDI(new BigDecimal("18"));     // spread = 10 ≥ 2.0
        now.setRsi(new BigDecimal("50"));         // in [38, 60]
        now.setAtr(new BigDecimal("200"));
        now.setBodyToRangeRatio(new BigDecimal("0.60"));
        now.setCloseLocationValue(new BigDecimal("0.80"));
        now.setRelativeVolume20(new BigDecimal("1.50"));
        now.setTrendRegime("BULL_TREND");

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T10:00:00"));
        md.setClosePrice(new BigDecimal("100500"));   // > ema20 (100400) — reclaim
        md.setHighPrice(new BigDecimal("100600"));
        md.setLowPrice(new BigDecimal("100400"));     // touched ema20
        md.setOpenPrice(new BigDecimal("100410"));

        // 4h bias — bullish stack with ADX in [25, 40].
        FeatureStore bias = new FeatureStore();
        bias.setEma50(new BigDecimal("99000"));
        bias.setEma200(new BigDecimal("96000"));
        bias.setAdx(new BigDecimal("32"));
        MarketData biasMd = new MarketData();
        biasMd.setClosePrice(new BigDecimal("100000"));
        biasMd.setEndTime(LocalDateTime.parse("2026-04-29T08:00:00"));

        return baseCtx(md, now, bias, biasMd, false, null);
    }

    private static EnrichedStrategyContext shortEntryContext() {
        FeatureStore now = new FeatureStore();
        now.setEma20(new BigDecimal("99800"));
        now.setEma50(new BigDecimal("100100"));
        now.setEma200(new BigDecimal("101500"));
        now.setEma50Slope(new BigDecimal("-5"));    // negative; |slope| ≥ 0
        now.setAdx(new BigDecimal("30"));
        now.setPlusDI(new BigDecimal("18"));
        now.setMinusDI(new BigDecimal("28"));       // bear spread = 10
        now.setRsi(new BigDecimal("50"));           // in [40, 62]
        now.setAtr(new BigDecimal("200"));
        now.setBodyToRangeRatio(new BigDecimal("0.60"));
        // SHORT mirror: clv must be in [1-clvMax=0.10, 1-clvMin=0.30]
        now.setCloseLocationValue(new BigDecimal("0.20"));
        now.setRelativeVolume20(new BigDecimal("1.50"));
        now.setTrendRegime("BEAR_TREND");

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T10:00:00"));
        md.setClosePrice(new BigDecimal("99700"));   // < ema20 (99800)
        md.setHighPrice(new BigDecimal("99800"));    // touched ema20
        md.setLowPrice(new BigDecimal("99600"));
        md.setOpenPrice(new BigDecimal("99780"));

        FeatureStore bias = new FeatureStore();
        bias.setEma50(new BigDecimal("100500"));
        bias.setEma200(new BigDecimal("103000"));
        bias.setAdx(new BigDecimal("32"));
        MarketData biasMd = new MarketData();
        biasMd.setClosePrice(new BigDecimal("100000"));
        biasMd.setEndTime(LocalDateTime.parse("2026-04-29T08:00:00"));

        return baseCtx(md, now, bias, biasMd, false, null);
    }

    private static EnrichedStrategyContext breakEvenContext() {
        FeatureStore now = new FeatureStore();
        now.setEma20(new BigDecimal("100500"));
        now.setAtr(new BigDecimal("200"));

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T11:00:00"));
        md.setClosePrice(new BigDecimal("101000"));    // entry+1R favourable
        md.setHighPrice(new BigDecimal("101100"));
        md.setLowPrice(new BigDecimal("100900"));

        PositionSnapshot snap = PositionSnapshot.builder()
                .hasOpenPosition(true)
                .side("LONG")
                .status("OPEN")
                .positionRole("TP1")
                .entryPrice(new BigDecimal("100000"))
                .entryQty(new BigDecimal("0.01"))
                .currentStopLossPrice(new BigDecimal("99000"))
                .initialStopLossPrice(new BigDecimal("99000"))
                .takeProfitPrice(new BigDecimal("102000"))
                .entryTime(LocalDateTime.parse("2026-04-29T10:00:00"))
                .build();

        return baseCtx(md, now, null, null, true, snap);
    }

    private static EnrichedStrategyContext runnerPhase2Context() {
        FeatureStore now = new FeatureStore();
        now.setEma20(new BigDecimal("100500"));
        now.setAtr(new BigDecimal("200"));

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T12:00:00"));
        md.setClosePrice(new BigDecimal("102000"));   // +2R favourable
        md.setHighPrice(new BigDecimal("102100"));
        md.setLowPrice(new BigDecimal("101900"));

        PositionSnapshot snap = PositionSnapshot.builder()
                .hasOpenPosition(true)
                .side("LONG")
                .status("OPEN")
                .positionRole("RUNNER")
                .entryPrice(new BigDecimal("100000"))
                .entryQty(new BigDecimal("0.005"))
                .currentStopLossPrice(new BigDecimal("100000"))   // already at BE
                .initialStopLossPrice(new BigDecimal("99000"))
                .takeProfitPrice(new BigDecimal("103000"))
                .entryTime(LocalDateTime.parse("2026-04-29T10:00:00"))
                .build();

        return baseCtx(md, now, null, null, true, snap);
    }

    private static EnrichedStrategyContext baseCtx(MarketData md, FeatureStore now,
                                                   FeatureStore bias, MarketData biasMd,
                                                   boolean hasPosition, PositionSnapshot snap) {
        AccountStrategy as = AccountStrategy.builder()
                .strategyCode("TPR")
                .capitalAllocationPct(new BigDecimal("50"))
                .build();
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "backtest");

        return EnrichedStrategyContext.builder()
                .accountStrategy(as)
                .interval("1h")
                .marketData(md)
                .featureStore(now)
                .biasFeatureStore(bias)
                .biasMarketData(biasMd)
                .positionSnapshot(snap)
                .hasOpenPosition(hasPosition)
                .openPositionCount(hasPosition ? 1 : 0)
                .cashBalance(new BigDecimal("10000"))
                .assetBalance(new BigDecimal("0.1"))
                .allowLong(Boolean.TRUE)
                .allowShort(Boolean.TRUE)
                .executionMetadata(meta)
                .build();
    }
}
