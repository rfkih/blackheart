package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.model.AccountStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link StrategyHelper#calculateRiskBasedNotional}.
 *
 * <p>Exercises the formula
 * {@code notional = min(cash × risk / stopDist%, cash × min(maxAlloc, 1.0))}
 * across the failure-and-fallback matrix, plus a few representative
 * happy-path numerics so a regression on the BigDecimal scale would surface.
 *
 * <p>BigDecimal value-equality uses {@code compareTo == 0} throughout —
 * {@code Object.equals} on BigDecimal is scale-sensitive
 * ({@code 100.00 ≠ 100.0000}) and would produce confusing failures.
 */
class StrategyHelperRiskBasedSizingTest {

    private final StrategyHelper helper = new StrategyHelper();

    private static final BigDecimal CASH = new BigDecimal("10000");
    private static final BigDecimal ENTRY = new BigDecimal("100000");
    private static final BigDecimal STOP_2PCT = new BigDecimal("98000");   // 2% below entry

    // ── Returns ZERO (caller falls back) ─────────────────────────────────

    @Test
    void returnsZero_whenContextIsNull() {
        BigDecimal n = helper.calculateRiskBasedNotional(
                null, ENTRY, STOP_2PCT, new BigDecimal("0.02"), new BigDecimal("1.00"));
        assertBdEq("0", n);
    }

    @Test
    void returnsZero_whenRiskPctIsNull() {
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctxWithCash(CASH), ENTRY, STOP_2PCT, null, new BigDecimal("1.00"));
        assertBdEq("0", n);
    }

    @Test
    void returnsZero_whenRiskPctIsZeroOrNegative() {
        EnrichedStrategyContext ctx = ctxWithCash(CASH);
        assertBdEq("0", helper.calculateRiskBasedNotional(ctx, ENTRY, STOP_2PCT, BigDecimal.ZERO, BigDecimal.ONE));
        assertBdEq("0", helper.calculateRiskBasedNotional(ctx, ENTRY, STOP_2PCT, new BigDecimal("-0.01"), BigDecimal.ONE));
    }

    @Test
    void returnsZero_whenEntryPriceIsNullOrZero() {
        EnrichedStrategyContext ctx = ctxWithCash(CASH);
        assertBdEq("0", helper.calculateRiskBasedNotional(
                ctx, null, STOP_2PCT, new BigDecimal("0.02"), BigDecimal.ONE));
        assertBdEq("0", helper.calculateRiskBasedNotional(
                ctx, BigDecimal.ZERO, STOP_2PCT, new BigDecimal("0.02"), BigDecimal.ONE));
    }

    @Test
    void returnsZero_whenStopLossIsNullOrZero() {
        EnrichedStrategyContext ctx = ctxWithCash(CASH);
        assertBdEq("0", helper.calculateRiskBasedNotional(
                ctx, ENTRY, null, new BigDecimal("0.02"), BigDecimal.ONE));
        assertBdEq("0", helper.calculateRiskBasedNotional(
                ctx, ENTRY, BigDecimal.ZERO, new BigDecimal("0.02"), BigDecimal.ONE));
    }

    @Test
    void returnsZero_whenStopEqualsEntry_degenerateStopDistance() {
        EnrichedStrategyContext ctx = ctxWithCash(CASH);
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctx, ENTRY, ENTRY, new BigDecimal("0.02"), BigDecimal.ONE);
        assertBdEq("0", n);
    }

    @Test
    void returnsZero_whenCashBalanceIsZeroOrNull() {
        BigDecimal risk = new BigDecimal("0.02");
        BigDecimal cap = BigDecimal.ONE;
        assertBdEq("0", helper.calculateRiskBasedNotional(
                ctxWithCash(BigDecimal.ZERO), ENTRY, STOP_2PCT, risk, cap));
        assertBdEq("0", helper.calculateRiskBasedNotional(
                ctxWithCash(null), ENTRY, STOP_2PCT, risk, cap));
    }

    // ── Happy path numerics ──────────────────────────────────────────────

    /**
     * 2% risk × $10k cash on a 2%-stop = $10,000 ideal notional.
     * Cap = 100% × $10k = $10,000. min(ideal, cap) = $10,000 (cap and ideal
     * both saturate the balance — capital fully deployed at expected risk).
     */
    @Test
    void typical_2pct_risk_2pct_stop_uses_full_capital() {
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctxWithCash(CASH), ENTRY, STOP_2PCT, new BigDecimal("0.02"), BigDecimal.ONE);
        assertBdEq("10000", n);
    }

    /**
     * 2% risk × $10k cash on a 4%-stop = $5,000 ideal notional (uses 50% of
     * capital). Cap = 100% × $10k = $10,000. min(5000, 10000) = $5,000.
     * Wider stop ⇒ smaller position to keep the same dollar risk.
     */
    @Test
    void wider_stop_reduces_position_proportionally() {
        BigDecimal stop4pct = new BigDecimal("96000");
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctxWithCash(CASH), ENTRY, stop4pct, new BigDecimal("0.02"), BigDecimal.ONE);
        assertBdEq("5000", n);
    }

    /**
     * 2% risk × $10k cash on a 1%-stop = $20,000 ideal notional (200% of
     * capital — would require leverage). Cap at 100% = $10,000. Output:
     * $10,000 (capped). The trade still executes; effective risk drops
     * to 1% of capital instead of the requested 2%.
     */
    @Test
    void tight_stop_caps_at_max_allocation_so_trade_still_executes() {
        BigDecimal stop1pct = new BigDecimal("99000");
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctxWithCash(CASH), ENTRY, stop1pct, new BigDecimal("0.02"), BigDecimal.ONE);
        assertBdEq("10000", n);
    }

    @Test
    void short_side_uses_absolute_stop_distance() {
        // SHORT: stop above entry. Same |distance| should size the same.
        BigDecimal entry = new BigDecimal("100000");
        BigDecimal stopAbove = new BigDecimal("102000");   // 2% above
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctxWithCash(CASH), entry, stopAbove, new BigDecimal("0.02"), BigDecimal.ONE);
        assertBdEq("10000", n);
    }

    // ── Cap precedence + clamp at 1.0 (Bug D) ────────────────────────────

    @Test
    void maxAllocation_above_1_is_clamped_at_1_no_leverage_in_backtest() {
        // Even if researcher sets maxAlloc=5.0 hoping for leverage, it must
        // clamp to 1.0 since the backtest engine is cash-1×.
        BigDecimal stop1pct = new BigDecimal("99000");   // ideal would be $20k
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctxWithCash(CASH), ENTRY, stop1pct, new BigDecimal("0.02"), new BigDecimal("5.00"));
        assertBdEq("10000", n);   // clamped to 1.0 × cash = $10k
    }

    @Test
    void maxAllocation_below_1_caps_lower_than_full_capital() {
        // User explicitly wants a smaller cap (e.g. 50%) — should win over
        // the ideal notional of $10k.
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctxWithCash(CASH), ENTRY, STOP_2PCT, new BigDecimal("0.02"), new BigDecimal("0.50"));
        assertBdEq("5000", n);
    }

    @Test
    void maxAllocation_null_falls_back_to_capitalAllocationPct() {
        // No max specified → use AccountStrategy.capitalAllocationPct (50%).
        // ideal = $10k × 0.02 / 0.02 = $10k; cap = 50% × $10k = $5k.
        EnrichedStrategyContext ctx = ctxWithCashAndAlloc(CASH, new BigDecimal("50"));
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctx, ENTRY, STOP_2PCT, new BigDecimal("0.02"), null);
        assertBdEq("5000", n);
    }

    @Test
    void maxAllocation_null_and_no_capitalAllocationPct_returns_zero() {
        // Both unset → no cap basis → returns ZERO so caller falls back.
        EnrichedStrategyContext ctx = ctxWithCashAndAlloc(CASH, null);
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctx, ENTRY, STOP_2PCT, new BigDecimal("0.02"), null);
        assertBdEq("0", n);
    }

    @Test
    void maxAllocation_zero_falls_back_to_capitalAllocationPct() {
        // Treat 0 / negative as "unspecified" — fall back to AccountStrategy.
        EnrichedStrategyContext ctx = ctxWithCashAndAlloc(CASH, new BigDecimal("25"));
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctx, ENTRY, STOP_2PCT, new BigDecimal("0.02"), BigDecimal.ZERO);
        // ideal = $10k; cap = 25% × $10k = $2,500.
        assertBdEq("2500", n);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static EnrichedStrategyContext ctxWithCash(BigDecimal cash) {
        return ctxWithCashAndAlloc(cash, new BigDecimal("100"));
    }

    private static EnrichedStrategyContext ctxWithCashAndAlloc(BigDecimal cash, BigDecimal allocPct) {
        AccountStrategy as = AccountStrategy.builder()
                .strategyCode("TPR")
                .capitalAllocationPct(allocPct)
                .build();
        return EnrichedStrategyContext.builder()
                .accountStrategy(as)
                .cashBalance(cash)
                .build();
    }

    private static void assertBdEq(String expected, BigDecimal actual) {
        BigDecimal exp = new BigDecimal(expected);
        assertTrue(actual != null,
                () -> "expected " + expected + " but got null");
        assertEquals(0, exp.compareTo(actual),
                () -> "expected " + expected + " but got " + actual);
    }
}
