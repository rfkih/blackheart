package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.util.TradeConstant.DecisionType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the executor's entry-sizing contract: LONG reads notionalSize
 * (USDT), SHORT reads positionSize (BTC). These tests are the reason that
 * contract is now a property, not a comment — see the regression they catch
 * in the {@code BugRegression} nested class.
 */
class StrategyDecisionInvariantsTest {

    private static final BigDecimal ENTRY = new BigDecimal("100000");

    private static StrategyDecision.StrategyDecisionBuilder validLong() {
        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .side("LONG")
                .notionalSize(new BigDecimal("250"))
                .stopLossPrice(new BigDecimal("99000"))
                .takeProfitPrice1(new BigDecimal("101500"));
    }

    private static StrategyDecision.StrategyDecisionBuilder validShort() {
        return StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_SHORT)
                .side("SHORT")
                .positionSize(new BigDecimal("0.005"))
                .stopLossPrice(new BigDecimal("101000"))
                .takeProfitPrice1(new BigDecimal("98500"));
    }

    @Test
    void noOpDecisionTypesProduceNoViolations() {
        for (DecisionType t : List.of(DecisionType.HOLD, DecisionType.CLOSE_LONG,
                DecisionType.CLOSE_SHORT, DecisionType.UPDATE_POSITION_MANAGEMENT)) {
            StrategyDecision d = StrategyDecision.builder().decisionType(t).build();
            assertTrue(StrategyDecisionInvariants.validate(d, ENTRY).isEmpty(),
                    "no entry-sizing contract on " + t);
        }
    }

    @Test
    void nullDecisionIsViolation() {
        List<String> v = StrategyDecisionInvariants.validate(null, ENTRY);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("null"));
    }

    @Test
    void nullDecisionTypeIsViolation() {
        List<String> v = StrategyDecisionInvariants.validate(
                StrategyDecision.builder().build(), ENTRY);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("decisionType"));
    }

    @Nested
    class OpenLong {

        @Test
        void happyPathHasNoViolations() {
            assertTrue(StrategyDecisionInvariants.validate(validLong().build(), ENTRY).isEmpty());
        }

        @Test
        void missingNotionalSizeIsViolation() {
            StrategyDecision d = validLong().notionalSize(null).build();
            List<String> v = StrategyDecisionInvariants.validate(d, ENTRY);
            assertTrue(hasMatch(v, "notionalSize"));
        }

        @Test
        void zeroNotionalSizeIsViolation() {
            StrategyDecision d = validLong().notionalSize(BigDecimal.ZERO).build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY), "notionalSize"));
        }

        @Test
        void negativeNotionalSizeIsViolation() {
            StrategyDecision d = validLong().notionalSize(new BigDecimal("-1")).build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY), "notionalSize"));
        }

        @Test
        void wrongSideLabelIsViolation() {
            StrategyDecision d = validLong().side("SHORT").build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY),
                    "side=LONG"));
        }

        @Test
        void stopAtOrAboveEntryIsViolation() {
            StrategyDecision d = validLong().stopLossPrice(ENTRY).build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY),
                    "stopLossPrice"));
        }

        @Test
        void tp1AtOrBelowEntryIsViolation() {
            StrategyDecision d = validLong().takeProfitPrice1(ENTRY).build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY),
                    "takeProfitPrice1"));
        }

        @Test
        void priceChecksSkippedWhenEntryIsNull() {
            // Strategy decisions get persisted to BacktestRun for replay;
            // older rows may not have an associated price.
            StrategyDecision d = validLong()
                    .stopLossPrice(new BigDecimal("999999"))   // wrong side
                    .takeProfitPrice1(new BigDecimal("0.01"))  // wrong side
                    .build();
            assertTrue(StrategyDecisionInvariants.validate(d, null).isEmpty());
        }
    }

    @Nested
    class OpenShort {

        @Test
        void happyPathHasNoViolations() {
            assertTrue(StrategyDecisionInvariants.validate(validShort().build(), ENTRY).isEmpty());
        }

        @Test
        void missingPositionSizeIsViolation() {
            StrategyDecision d = validShort().positionSize(null).build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY), "positionSize"));
        }

        @Test
        void wrongSideLabelIsViolation() {
            StrategyDecision d = validShort().side("LONG").build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY),
                    "side=SHORT"));
        }

        @Test
        void stopAtOrBelowEntryIsViolation() {
            StrategyDecision d = validShort().stopLossPrice(ENTRY).build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY),
                    "stopLossPrice"));
        }

        @Test
        void tp1AtOrAboveEntryIsViolation() {
            StrategyDecision d = validShort().takeProfitPrice1(ENTRY).build();
            assertTrue(hasMatch(StrategyDecisionInvariants.validate(d, ENTRY),
                    "takeProfitPrice1"));
        }
    }

    @Nested
    class BugRegression {

        /** The exact bug LSR/VCB SHORT had until 2026-04-26: SHORT decision
         *  set notionalSize (USDT) instead of positionSize (BTC). */
        @Test
        void shortWithNotionalSizeButNoPositionSizeIsViolation() {
            StrategyDecision broken = StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_SHORT)
                    .side("SHORT")
                    .notionalSize(new BigDecimal("250"))   // wrong field for SHORT
                    .stopLossPrice(new BigDecimal("101000"))
                    .takeProfitPrice1(new BigDecimal("98500"))
                    .build();
            List<String> v = StrategyDecisionInvariants.validate(broken, ENTRY);
            assertTrue(hasMatch(v, "positionSize"),
                    "regression guard: SHORT must reject notionalSize-only decisions");
        }

        /** Mirror bug for LONG that the executor would also silently absorb. */
        @Test
        void longWithPositionSizeButNoNotionalSizeIsViolation() {
            StrategyDecision broken = StrategyDecision.builder()
                    .decisionType(DecisionType.OPEN_LONG)
                    .side("LONG")
                    .positionSize(new BigDecimal("0.005")) // wrong field for LONG
                    .stopLossPrice(new BigDecimal("99000"))
                    .takeProfitPrice1(new BigDecimal("101500"))
                    .build();
            List<String> v = StrategyDecisionInvariants.validate(broken, ENTRY);
            assertTrue(hasMatch(v, "notionalSize"),
                    "regression guard: LONG must reject positionSize-only decisions");
        }
    }

    private static boolean hasMatch(List<String> violations, String needle) {
        assertFalse(violations.isEmpty(), "expected at least one violation");
        return violations.stream().anyMatch(s -> s.contains(needle));
    }
}
