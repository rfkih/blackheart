package id.co.blackheart.dto.backtest;

import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the Phase B1 invariants on {@link BacktestState}:
 *  - The multi-trade map and the legacy {@code activeTrade} slot stay
 *    consistent across mutations.
 *  - {@code countActiveTrades} reflects the multi-trade map exactly.
 *  - Removing the last trade clears the legacy slot to null;
 *    removing a non-last trade leaves the legacy slot pointing at
 *    one of the remaining trades.
 */
class BacktestStateTest {

    private static BacktestRun runWithCapital(String capital) {
        BacktestRun r = new BacktestRun();
        r.setInitialCapital(new BigDecimal(capital));
        return r;
    }

    private static BacktestTrade trade(String code) {
        BacktestTrade t = new BacktestTrade();
        t.setStrategyName(code);
        return t;
    }

    @Test
    void initialStateHasZeroActiveTradesAndNullLegacySlot() {
        BacktestState state = BacktestState.initial(runWithCapital("10000"));
        assertEquals(0, state.countActiveTrades());
        assertNull(state.getActiveTrade());
        assertFalse(state.hasActiveTradeFor("LSR"));
    }

    @Test
    void addingFirstTradePopulatesBothMapAndLegacySlot() {
        BacktestState state = BacktestState.initial(runWithCapital("10000"));
        BacktestTrade lsr = trade("LSR");

        state.addActiveTrade("LSR", lsr, List.of());

        assertEquals(1, state.countActiveTrades());
        assertTrue(state.hasActiveTradeFor("LSR"));
        assertSame(lsr, state.getActiveTrade(),
                "legacy slot must mirror the open trade");
    }

    @Test
    void multipleTradesCoexistAndCountIsAccurate() {
        BacktestState state = BacktestState.initial(runWithCapital("10000"));
        state.addActiveTrade("LSR", trade("LSR"), List.of());
        state.addActiveTrade("VCB", trade("VCB"), List.of());
        state.addActiveTrade("TPR", trade("TPR"), List.of());

        assertEquals(3, state.countActiveTrades());
        assertTrue(state.hasActiveTradeFor("LSR"));
        assertTrue(state.hasActiveTradeFor("VCB"));
        assertTrue(state.hasActiveTradeFor("TPR"));
        assertFalse(state.hasActiveTradeFor("VBO"));
    }

    @Test
    void removingNonLastTradeKeepsLegacySlotPointingAtAnExistingTrade() {
        BacktestState state = BacktestState.initial(runWithCapital("10000"));
        state.addActiveTrade("LSR", trade("LSR"), List.of());
        state.addActiveTrade("VCB", trade("VCB"), List.of());

        state.removeActiveTrade("LSR");

        assertEquals(1, state.countActiveTrades());
        assertFalse(state.hasActiveTradeFor("LSR"));
        assertTrue(state.hasActiveTradeFor("VCB"));
        // Legacy slot should now mirror the remaining VCB trade.
        assertNotNull(state.getActiveTrade());
        assertEquals("VCB", state.getActiveTrade().getStrategyName());
    }

    @Test
    void removingLastTradeClearsLegacySlot() {
        BacktestState state = BacktestState.initial(runWithCapital("10000"));
        state.addActiveTrade("LSR", trade("LSR"), List.of());

        state.removeActiveTrade("LSR");

        assertEquals(0, state.countActiveTrades());
        assertNull(state.getActiveTrade(),
                "legacy slot must clear when no trades remain");
        assertTrue(state.getActiveTradePositions() == null
                || state.getActiveTradePositions().isEmpty());
    }

    @Test
    void removingUnknownStrategyIsANoOp() {
        BacktestState state = BacktestState.initial(runWithCapital("10000"));
        state.addActiveTrade("LSR", trade("LSR"), List.of());

        state.removeActiveTrade("DOES_NOT_EXIST");

        // LSR still there; legacy slot still points at LSR.
        assertEquals(1, state.countActiveTrades());
        assertTrue(state.hasActiveTradeFor("LSR"));
    }

    @Test
    void addingNullStrategyOrTradeIsANoOp() {
        BacktestState state = BacktestState.initial(runWithCapital("10000"));
        state.addActiveTrade(null, trade("LSR"), List.of());
        state.addActiveTrade("LSR", null, List.of());
        assertEquals(0, state.countActiveTrades());
    }

    @Test
    void positionsAreTrackedPerStrategy() {
        BacktestState state = BacktestState.initial(runWithCapital("10000"));
        BacktestTradePosition lsrPos = new BacktestTradePosition();
        lsrPos.setStatus("OPEN");
        BacktestTradePosition vcbPos = new BacktestTradePosition();
        vcbPos.setStatus("OPEN");

        state.addActiveTrade("LSR", trade("LSR"), List.of(lsrPos));
        state.addActiveTrade("VCB", trade("VCB"), List.of(vcbPos));

        assertEquals(1, state.getActiveTradePositionsByStrategy().get("LSR").size());
        assertEquals(1, state.getActiveTradePositionsByStrategy().get("VCB").size());
    }
}
