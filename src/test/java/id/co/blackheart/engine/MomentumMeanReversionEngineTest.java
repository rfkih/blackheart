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
 * Behaviour tests for {@link MomentumMeanReversionEngine}. Anchor EMA = ema200,
 * target EMA = ema50 by default; entry fires on extreme deviation from anchor
 * with RSI confirming exhaustion, TP at the closer EMA target.
 */
class MomentumMeanReversionEngineTest {

    private final StrategyHelper helper = new StrategyHelper();
    private final MomentumMeanReversionEngine engine = new MomentumMeanReversionEngine(helper);

    @Test
    void longEntry_passesAllGates_emitsOpenLongFadingToTarget() {
        EnrichedStrategyContext ctx = longEntryContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.OPEN_LONG, d.getDecisionType());
        assertEquals("LONG", d.getSide());
        assertNotNull(d.getNotionalSize());
        assertNull(d.getPositionSize());
        // close=99500, atr=200, stopAtrBuffer=1.0 → stop=99300
        assertBdEq("99300", d.getStopLossPrice());
        // target ema50 = 100000 → tp1=100000
        assertBdEq("100000", d.getTakeProfitPrice1());
        assertEquals("SINGLE", d.getExitStructure());
        assertTrue(d.getTags().contains("ENTRY"));
    }

    @Test
    void shortEntry_passesAllGates_emitsOpenShortFadingToTarget() {
        EnrichedStrategyContext ctx = shortEntryContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.OPEN_SHORT, d.getDecisionType());
        assertEquals("SHORT", d.getSide());
        assertNotNull(d.getPositionSize());
        assertNull(d.getNotionalSize());
        assertEquals("SINGLE", d.getExitStructure());
    }

    @Test
    void closeNotFarEnoughBelowAnchor_holds() {
        EnrichedStrategyContext ctx = longEntryContext();
        // close just barely below anchor — extreme gate fails.
        ctx.getMarketData().setClosePrice(new BigDecimal("100100"));
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void rsiNotOversold_holds() {
        EnrichedStrategyContext ctx = longEntryContext();
        ctx.getFeatureStore().setRsi(new BigDecimal("45"));   // > 30 oversoldMax
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void targetBelowClose_holdsForLong() {
        EnrichedStrategyContext ctx = longEntryContext();
        // target ema50 set below close → no upside reversion.
        ctx.getFeatureStore().setEma50(new BigDecimal("99000"));
        StrategyDecision d = engine.evaluate(spec(), ctx);
        assertEquals(DecisionType.HOLD, d.getDecisionType());
    }

    @Test
    void breakEvenShift_movesStopToEntryAtOneR() {
        EnrichedStrategyContext ctx = breakEvenContext();
        StrategyDecision d = engine.evaluate(spec(), ctx);

        assertEquals(DecisionType.UPDATE_POSITION_MANAGEMENT, d.getDecisionType());
        // entry=99500, initStop=99300, init risk=200, +1R reached at close=99700.
        assertBdEq("99500", d.getStopLossPrice());
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
                .strategyCode("MMR")
                .strategyName("MMR")
                .archetype(MomentumMeanReversionEngine.ARCHETYPE)
                .archetypeVersion(MomentumMeanReversionEngine.VERSION)
                .specSchemaVersion(1)
                .params(new HashMap<>())
                .body(body)
                .build();
    }

    private static EnrichedStrategyContext longEntryContext() {
        FeatureStore now = new FeatureStore();
        now.setEma200(new BigDecimal("100000"));   // anchor
        now.setEma50(new BigDecimal("100000"));    // target above close → reversion upside
        now.setRsi(new BigDecimal("25"));          // ≤ 30 oversoldMax
        now.setAtr(new BigDecimal("200"));
        now.setAdx(new BigDecimal("18"));
        now.setTrendRegime("CHOP");

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T10:00:00"));
        // close = 99500 → 2.5·ATR below anchor (above 2.0 floor) — qualifies.
        md.setClosePrice(new BigDecimal("99500"));
        md.setHighPrice(new BigDecimal("99700"));
        md.setLowPrice(new BigDecimal("99400"));
        md.setOpenPrice(new BigDecimal("99650"));

        return baseCtx(md, now, false, null);
    }

    private static EnrichedStrategyContext shortEntryContext() {
        FeatureStore now = new FeatureStore();
        now.setEma200(new BigDecimal("100000"));
        now.setEma50(new BigDecimal("100000"));    // target below close → reversion downside
        now.setRsi(new BigDecimal("75"));          // ≥ 70 overboughtMin
        now.setAtr(new BigDecimal("200"));
        now.setAdx(new BigDecimal("18"));
        now.setTrendRegime("CHOP");

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T10:00:00"));
        // close = 100500 → 2.5·ATR above anchor.
        md.setClosePrice(new BigDecimal("100500"));
        md.setHighPrice(new BigDecimal("100600"));
        md.setLowPrice(new BigDecimal("100300"));
        md.setOpenPrice(new BigDecimal("100350"));

        return baseCtx(md, now, false, null);
    }

    private static EnrichedStrategyContext breakEvenContext() {
        FeatureStore now = new FeatureStore();
        now.setEma200(new BigDecimal("100000"));
        now.setEma50(new BigDecimal("100000"));
        now.setAtr(new BigDecimal("200"));

        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T11:00:00"));
        md.setClosePrice(new BigDecimal("99700"));   // entry+1R favourable
        md.setHighPrice(new BigDecimal("99750"));
        md.setLowPrice(new BigDecimal("99550"));

        PositionSnapshot snap = PositionSnapshot.builder()
                .hasOpenPosition(true)
                .side("LONG")
                .status("OPEN")
                .positionRole("ALL")
                .entryPrice(new BigDecimal("99500"))
                .entryQty(new BigDecimal("0.01"))
                .currentStopLossPrice(new BigDecimal("99300"))
                .initialStopLossPrice(new BigDecimal("99300"))
                .takeProfitPrice(new BigDecimal("100000"))
                .entryTime(LocalDateTime.parse("2026-04-29T10:00:00"))
                .build();

        return baseCtx(md, now, true, snap);
    }

    private static EnrichedStrategyContext baseCtx(MarketData md, FeatureStore now,
                                                   boolean hasPosition, PositionSnapshot snap) {
        AccountStrategy as = AccountStrategy.builder()
                .strategyCode("MMR")
                .capitalAllocationPct(new BigDecimal("50"))
                .build();
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "backtest");

        return EnrichedStrategyContext.builder()
                .accountStrategy(as)
                .interval("1h")
                .marketData(md)
                .featureStore(now)
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
