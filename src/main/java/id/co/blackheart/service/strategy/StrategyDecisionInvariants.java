package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.util.TradeConstant.DecisionType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-function correctness checks for {@link StrategyDecision} entries.
 *
 * <p>The platform discovered (2026-04-26) that four of six strategy services
 * were producing decisions that violated the executor's contract — wrong
 * sizing field, wrong currency, or stops on the wrong side of entry — and
 * the executor silently absorbed every one. Bugs of this shape don't surface
 * as exceptions; they surface as missing trades or double-sized risk.
 *
 * <p>This class encodes the contract as data so it can be (a) asserted in
 * tests and (b) used as a runtime guard. Each invariant is documented with
 * the executor invariant it protects.
 *
 * <p>Returns a list of human-readable violations rather than throwing so
 * callers can choose: tests use {@code assertThat(violations).isEmpty()};
 * runtime callers log + alert without aborting the live loop.
 */
public final class StrategyDecisionInvariants {

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private StrategyDecisionInvariants() {}

    /**
     * Validate a decision against the entry-reference price (typically the
     * current bar's close — what the executor will fill at). Pass {@code null}
     * when no price is available; price-based invariants are then skipped.
     */
    public static List<String> validate(StrategyDecision decision, BigDecimal entryRefPrice) {
        if (decision == null) {
            return List.of("decision is null");
        }
        DecisionType type = decision.getDecisionType();
        if (type == null) {
            return List.of("decisionType is null");
        }
        switch (type) {
            case OPEN_LONG:
                return validateOpenLong(decision, entryRefPrice);
            case OPEN_SHORT:
                return validateOpenShort(decision, entryRefPrice);
            default:
                // HOLD / CLOSE_* / UPDATE_POSITION_MANAGEMENT — no entry-sizing
                // contract to enforce here.
                return Collections.emptyList();
        }
    }

    private static List<String> validateOpenLong(StrategyDecision d, BigDecimal entry) {
        List<String> v = new ArrayList<>();

        if (!SIDE_LONG.equalsIgnoreCase(d.getSide())) {
            v.add("OPEN_LONG must carry side=LONG, got " + d.getSide());
        }

        // Executor contract: executeOpenLong reads decision.getNotionalSize()
        // and matches against the USDT portfolio balance.
        if (notPositive(d.getNotionalSize())) {
            v.add("OPEN_LONG must set notionalSize > 0 (USDT). The executor reads this field for LONG entries; "
                    + "setting positionSize instead causes a silent fallback to executor-default sizing.");
        }

        if (entry != null && entry.compareTo(ZERO) > 0) {
            if (notPositive(d.getStopLossPrice())) {
                v.add("OPEN_LONG must set a positive stopLossPrice");
            } else if (d.getStopLossPrice().compareTo(entry) >= 0) {
                v.add("OPEN_LONG stopLossPrice (" + d.getStopLossPrice() + ") must be strictly below entry ("
                        + entry + ")");
            }

            if (d.getTakeProfitPrice1() != null && d.getTakeProfitPrice1().compareTo(entry) <= 0) {
                v.add("OPEN_LONG takeProfitPrice1 (" + d.getTakeProfitPrice1() + ") must be strictly above entry ("
                        + entry + ")");
            }
            if (d.getTakeProfitPrice2() != null && d.getTakeProfitPrice2().compareTo(entry) <= 0) {
                v.add("OPEN_LONG takeProfitPrice2 (" + d.getTakeProfitPrice2() + ") must be strictly above entry ("
                        + entry + ")");
            }
        }
        return v;
    }

    private static List<String> validateOpenShort(StrategyDecision d, BigDecimal entry) {
        List<String> v = new ArrayList<>();

        if (!SIDE_SHORT.equalsIgnoreCase(d.getSide())) {
            v.add("OPEN_SHORT must carry side=SHORT, got " + d.getSide());
        }

        // Executor contract: executeOpenShort reads decision.getPositionSize()
        // and matches against the BTC portfolio balance. notionalSize is
        // ignored on the SHORT path.
        if (notPositive(d.getPositionSize())) {
            v.add("OPEN_SHORT must set positionSize > 0 (BTC quantity). The executor reads this field for SHORT "
                    + "entries; setting notionalSize instead causes a silent fallback to executor-default sizing.");
        }

        if (entry != null && entry.compareTo(ZERO) > 0) {
            if (notPositive(d.getStopLossPrice())) {
                v.add("OPEN_SHORT must set a positive stopLossPrice");
            } else if (d.getStopLossPrice().compareTo(entry) <= 0) {
                v.add("OPEN_SHORT stopLossPrice (" + d.getStopLossPrice() + ") must be strictly above entry ("
                        + entry + ")");
            }

            if (d.getTakeProfitPrice1() != null && d.getTakeProfitPrice1().compareTo(entry) >= 0) {
                v.add("OPEN_SHORT takeProfitPrice1 (" + d.getTakeProfitPrice1() + ") must be strictly below entry ("
                        + entry + ")");
            }
            if (d.getTakeProfitPrice2() != null && d.getTakeProfitPrice2().compareTo(entry) >= 0) {
                v.add("OPEN_SHORT takeProfitPrice2 (" + d.getTakeProfitPrice2() + ") must be strictly below entry ("
                        + entry + ")");
            }
        }
        return v;
    }

    private static boolean notPositive(BigDecimal value) {
        return value == null || value.compareTo(ZERO) <= 0;
    }
}
