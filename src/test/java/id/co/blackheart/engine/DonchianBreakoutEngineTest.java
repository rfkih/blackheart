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

/**
 * Behaviour tests for {@link DonchianBreakoutEngine}. We exercise each gate
 * path against curated fixtures. DCT itself was discarded (research lessons
 * memo): the engine here generalises the archetype with the lessons baked
 * in (rvolMin floor, ADX gate, SINGLE exit shape).
 */
class DonchianBreakoutEngineTest {

    private final StrategyHelper helper = new StrategyHelper();
    private final DonchianBreakoutEngine engine = new DonchianBreakoutEngine(helper);

    @Test
    void longEntry_passesAllGates_emitsOpenLongWithCorrectStopAndTp() {
        EnrichedStrategyContext ctx = longEntryContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.OPEN_LONG, d.getDecisionType());
        assertEquals("LONG", d.getSide());
        assertNotNull(d.getNotionalSize(), "LONG must size in USDT");
        assertNull(d.getPositionSize(), "LONG must not carry positionSize");
        // Entry=100500, ATR=200, stopAtrMult=3.0 → stop=100500-600=99900
        assertBdEq("99900", d.getStopLossPrice());
        // Risk=600, tpR=2.0 → tp1=100500+1200=101700
        assertBdEq("101700", d.getTakeProfitPrice1());
        assertEquals("SINGLE", d.getExitStructure());
        assertEquals("ALL", d.getTargetPositionRole());
        assertTrue(d.getTags().contains("ENTRY"));
    }

    @Test
    void shortEntry_passesAllGates_emitsOpenShortWithBtcQty() {
        EnrichedStrategyContext ctx = shortEntryContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.OPEN_SHORT, d.getDecisionType());
        assertEquals("SHORT", d.getSide());
        assertNotNull(d.getPositionSize(), "SHORT must size in BTC");
        assertNull(d.getNotionalSize());
        assertEquals("SINGLE", d.getExitStructure());
    }

    @Test
    void closeNotAboveDonchianUpper_holds() {
        EnrichedStrategyContext ctx = longEntryContext();
        // Push channel above current close (100500) so the breakout gate fails.
        ctx.getPreviousFeatureStore().setDonchianUpper20(new BigDecimal("100600"));
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void rvolBelowFloor_holds() {
        EnrichedStrategyContext ctx = longEntryContext();
        ctx.getFeatureStore().setRelativeVolume20(new BigDecimal("1.10"));   // < 1.30 floor
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void adxBelowFloor_holds() {
        EnrichedStrategyContext ctx = longEntryContext();
        ctx.getFeatureStore().setAdx(new BigDecimal("15"));   // < 20 floor
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void closeAtPrevCloseLevel_belowChannel_holds() {
        EnrichedStrategyContext ctx = longEntryContext();
        // close=100400 < donchianUpper=100450 → breakout gate fails (close must exceed channel).
        ctx.getMarketData().setClosePrice(new BigDecimal("100400"));
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void breakEvenShift_movesStopToEntryAtOneR() {
        EnrichedStrategyContext ctx = breakEvenContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.UPDATE_POSITION_MANAGEMENT, d.getDecisionType());
        assertEquals("LONG", d.getSide());
        // entry=100000, initStop=99400, init risk=600, +1R reached at close=100600.
        assertBdEq("100000", d.getStopLossPrice());
        assertTrue(d.getTags().contains("BREAK_EVEN"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void assertBdEq(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but got null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static StrategySpec spec() {
        Map<String, Object> body = new HashMap<>();
        body.put("params", new HashMap<>());
        return StrategySpec.builder()
                .strategyCode("DCB")
                .strategyName("DCB")
                .archetype(DonchianBreakoutEngine.ARCHETYPE)
                .archetypeVersion(DonchianBreakoutEngine.VERSION)
                .specSchemaVersion(1)
                .params(new HashMap<>())
                .body(body)
                .build();
    }

    private static EnrichedStrategyContext longEntryContext() {
        FeatureStore now = new FeatureStore();
        now.setAdx(new BigDecimal("25"));        // ≥ 20 floor
        now.setAtr(new BigDecimal("200"));
        now.setRelativeVolume20(new BigDecimal("1.50"));   // ≥ 1.30 floor
        now.setTrendRegime("BULL_TREND");

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T10:00:00"));
        md.setClosePrice(new BigDecimal("100500"));   // continuation higher than prev
        md.setHighPrice(new BigDecimal("100600"));
        md.setLowPrice(new BigDecimal("100350"));
        md.setOpenPrice(new BigDecimal("100380"));

        // prev close = 100400, donchian upper = 100450 (realistic: >= prevBar.high >= prevClose)
        // current close = 100500 breaks above 100450 → long breakout fires
        FeatureStore prev = new FeatureStore();
        prev.setPrice(new BigDecimal("100400"));
        prev.setDonchianUpper20(new BigDecimal("100450"));
        prev.setDonchianLower20(new BigDecimal("99000"));

        return baseCtx(md, now, prev, false, null);
    }

    private static EnrichedStrategyContext shortEntryContext() {
        FeatureStore now = new FeatureStore();
        now.setAdx(new BigDecimal("25"));
        now.setAtr(new BigDecimal("200"));
        now.setRelativeVolume20(new BigDecimal("1.50"));
        now.setTrendRegime("BEAR_TREND");

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T10:00:00"));
        md.setClosePrice(new BigDecimal("99500"));    // continuation lower than prev
        md.setHighPrice(new BigDecimal("99650"));
        md.setLowPrice(new BigDecimal("99400"));
        md.setOpenPrice(new BigDecimal("99620"));

        // prev close = 99600, donchian lower = 99550 (realistic: <= prevBar.low <= prevClose)
        // current close = 99500 breaks below 99550 → short breakdown fires
        FeatureStore prev = new FeatureStore();
        prev.setPrice(new BigDecimal("99600"));
        prev.setDonchianUpper20(new BigDecimal("101000"));
        prev.setDonchianLower20(new BigDecimal("99550"));

        return baseCtx(md, now, prev, false, null);
    }

    private static EnrichedStrategyContext breakEvenContext() {
        FeatureStore now = new FeatureStore();
        now.setAtr(new BigDecimal("200"));

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T11:00:00"));
        md.setClosePrice(new BigDecimal("100600"));   // entry+1R favourable
        md.setHighPrice(new BigDecimal("100700"));
        md.setLowPrice(new BigDecimal("100500"));

        PositionSnapshot snap = PositionSnapshot.builder()
                .hasOpenPosition(true)
                .side("LONG")
                .status("OPEN")
                .positionRole("ALL")
                .entryPrice(new BigDecimal("100000"))
                .entryQty(new BigDecimal("0.01"))
                .currentStopLossPrice(new BigDecimal("99400"))
                .initialStopLossPrice(new BigDecimal("99400"))
                .takeProfitPrice(new BigDecimal("101200"))
                .entryTime(LocalDateTime.parse("2026-04-29T10:00:00"))
                .build();

        return baseCtx(md, now, null, true, snap);
    }

    private static EnrichedStrategyContext baseCtx(MarketData md, FeatureStore now,
                                                   FeatureStore prev,
                                                   boolean hasPosition, PositionSnapshot snap) {
        AccountStrategy as = AccountStrategy.builder()
                .strategyCode("DCB")
                .capitalAllocationPct(new BigDecimal("50"))
                .build();
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "backtest");

        return EnrichedStrategyContext.builder()
                .accountStrategy(as)
                .interval("1h")
                .marketData(md)
                .featureStore(now)
                .previousFeatureStore(prev)
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
