package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.model.AccountStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void wrongSidedStopReturnsZero_longHelperRefusesShortStop() {
        // V55 — calculateRiskBasedNotional now enforces stop < entry (LONG
        // semantics). A SHORT-style stop (above entry) returns ZERO instead
        // of using |entry − stop|. SHORT callers must use
        // {@link StrategyHelper#calculateRiskBasedShortQty} which has the
        // mirror guard (stop > entry).
        BigDecimal entry = new BigDecimal("100000");
        BigDecimal stopAbove = new BigDecimal("102000");
        BigDecimal n = helper.calculateRiskBasedNotional(
                ctxWithCash(CASH), entry, stopAbove, new BigDecimal("0.02"), BigDecimal.ONE);
        assertBdEq("0", n);
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

    // ── V55 — calculateLongEntryNotional toggle picker ───────────────────

    @Test
    void longEntry_toggleOff_usesLegacyAllocationSizing() {
        // Toggle FALSE → returns calculateEntryNotional which is cash × allocFraction.
        // 25% × $10k = $2,500, regardless of stop distance.
        EnrichedStrategyContext ctx = ctxWithToggle(
                CASH, new BigDecimal("25"), /*useRisk=*/false, new BigDecimal("0.05"));
        BigDecimal n = helper.calculateLongEntryNotional(ctx, ENTRY, STOP_2PCT);
        assertBdEq("2500.00000000", n);
    }

    @Test
    void longEntry_toggleOn_usesRiskBasedSizing() {
        // Toggle TRUE, 5% risk × $10k cash on a 2% stop → ideal $25k, capped at
        // 100% × $10k. Output: $10,000.
        EnrichedStrategyContext ctx = ctxWithToggle(
                CASH, new BigDecimal("100"), /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal n = helper.calculateLongEntryNotional(ctx, ENTRY, STOP_2PCT);
        assertBdEq("10000", n);
    }

    @Test
    void longEntry_toggleOn_riskBasedRespectsAllocationCap() {
        // Toggle TRUE, 5% risk on a 2% stop → ideal $25k. Cap = 50% × $10k = $5k.
        // The user-set cap wins.
        EnrichedStrategyContext ctx = ctxWithToggle(
                CASH, new BigDecimal("50"), /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal n = helper.calculateLongEntryNotional(ctx, ENTRY, STOP_2PCT);
        assertBdEq("5000", n);
    }

    @Test
    void longEntry_toggleOn_degenerateStopReturnsZero() {
        // Toggle TRUE and riskPct valid → strict risk-based sizing.
        // Degenerate stop (stop == entry) returns ZERO so the strategy holds.
        // We do NOT fall back to legacy here — the operator opted into
        // controlled risk; silently sizing a full-allocation position would
        // defeat the toggle's intent.
        EnrichedStrategyContext ctx = ctxWithToggle(
                CASH, new BigDecimal("25"), /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal n = helper.calculateLongEntryNotional(ctx, ENTRY, ENTRY);
        assertBdEq("0", n);
    }

    @Test
    void longEntry_toggleOn_wrongSidedStopReturnsZero() {
        // LONG side-correctness guard: stop must be < entry. A stop ABOVE
        // entry would size off |entry − stop| and open a guaranteed-loss
        // trade. Defense in depth — return ZERO so the strategy holds.
        BigDecimal stopAbove = new BigDecimal("105000"); // above ENTRY=100000
        EnrichedStrategyContext ctx = ctxWithToggle(
                CASH, new BigDecimal("100"), /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal n = helper.calculateLongEntryNotional(ctx, ENTRY, stopAbove);
        assertBdEq("0", n);
    }

    @Test
    void longEntry_toggleOn_cashZeroReturnsZero() {
        // Risk-based sizing opted-in but cash=0 → no risk budget → ZERO.
        // Strategy holds. Previously this fell back to legacy which also
        // returns ZERO for LONG (cashBasedNotional checks cash > 0), but
        // the SHORT side fallback used to over-trade — now both sides hold.
        EnrichedStrategyContext ctx = ctxWithToggle(
                BigDecimal.ZERO, new BigDecimal("100"),
                /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal n = helper.calculateLongEntryNotional(ctx, ENTRY, STOP_2PCT);
        assertBdEq("0", n);
    }

    @Test
    void longEntry_toggleOnButRiskPctNull_usesLegacy() {
        // Defensive — if the toggle is on but riskPct is null/zero, treat the
        // configuration as "not risk-based" and use legacy sizing instead of
        // returning ZERO and killing the trade.
        EnrichedStrategyContext ctx = ctxWithToggle(
                CASH, new BigDecimal("25"), /*useRisk=*/true, /*riskPct=*/null);
        BigDecimal n = helper.calculateLongEntryNotional(ctx, ENTRY, STOP_2PCT);
        assertBdEq("2500.00000000", n);
    }

    // ── V55 — SHORT entry sizing (calculateShortEntryQty) ────────────────

    /** SHORT entry stop is ABOVE entry; |stop - entry| same as LONG case. */
    private static final BigDecimal STOP_2PCT_SHORT = new BigDecimal("102000");

    @Test
    void shortEntry_toggleOff_usesLegacyAllocationSizing() {
        // Toggle FALSE → returns calculateShortPositionSize = assetBalance × allocFraction.
        // assetBalance=1 (synthetic backtest cash/price), allocPct=25 → 0.25 BTC.
        EnrichedStrategyContext ctx = shortCtxWithToggle(
                CASH, new BigDecimal("1"), new BigDecimal("25"),
                /*useRisk=*/false, new BigDecimal("0.05"));
        BigDecimal qty = helper.calculateShortEntryQty(ctx, ENTRY, STOP_2PCT_SHORT);
        assertBdEq("0.25000000", qty);
    }

    @Test
    void shortEntry_toggleOn_usesRiskBasedSizing() {
        // Toggle TRUE, 5% risk × $10k cash = $500 risk budget.
        // stopDistance = 2000 USDT/BTC → idealQty = 500 / 2000 = 0.25 BTC.
        // assetBalance=1, allocPct=100 → cap = 1.0 BTC. Result = min(0.25, 1.0) = 0.25.
        EnrichedStrategyContext ctx = shortCtxWithToggle(
                CASH, new BigDecimal("1"), new BigDecimal("100"),
                /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal qty = helper.calculateShortEntryQty(ctx, ENTRY, STOP_2PCT_SHORT);
        assertBdEq("0.25", qty);
    }

    @Test
    void shortEntry_toggleOn_riskBasedRespectsInventoryCap() {
        // Toggle TRUE but inventory cap binds: assetBalance=0.1, allocPct=100 → cap=0.1.
        // idealQty would be 0.25 from risk math, but we cap at owned inventory.
        EnrichedStrategyContext ctx = shortCtxWithToggle(
                CASH, new BigDecimal("0.1"), new BigDecimal("100"),
                /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal qty = helper.calculateShortEntryQty(ctx, ENTRY, STOP_2PCT_SHORT);
        assertBdEq("0.1", qty);
    }

    @Test
    void shortEntry_toggleOn_allocCapShrinksInventoryFurther() {
        // assetBalance=1, allocPct=20 → cap=0.20 BTC. ideal=0.25 BTC. Result=0.20.
        EnrichedStrategyContext ctx = shortCtxWithToggle(
                CASH, new BigDecimal("1"), new BigDecimal("20"),
                /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal qty = helper.calculateShortEntryQty(ctx, ENTRY, STOP_2PCT_SHORT);
        assertBdEq("0.20", qty);
    }

    @Test
    void shortEntry_toggleOn_degenerateStopReturnsZero() {
        // Strict semantics — stop=entry returns ZERO; do NOT fall back to
        // legacy SHORT (which would over-trade the full BTC inventory).
        EnrichedStrategyContext ctx = shortCtxWithToggle(
                CASH, new BigDecimal("1"), new BigDecimal("25"),
                /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal qty = helper.calculateShortEntryQty(ctx, ENTRY, ENTRY);
        assertBdEq("0", qty);
    }

    @Test
    void shortEntry_toggleOn_wrongSidedStopReturnsZero() {
        // SHORT side-correctness guard: stop must be > entry. Stop BELOW
        // entry implies an immediate-loss SHORT — return ZERO so the
        // strategy holds rather than sizing off |entry − stop|.
        BigDecimal stopBelow = new BigDecimal("95000"); // below ENTRY=100000
        EnrichedStrategyContext ctx = shortCtxWithToggle(
                CASH, new BigDecimal("1"), new BigDecimal("100"),
                /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal qty = helper.calculateShortEntryQty(ctx, ENTRY, stopBelow);
        assertBdEq("0", qty);
    }

    @Test
    void shortEntry_toggleOn_noAssetBalanceReturnsZero() {
        // No BTC inventory and risk-based opted-in → strategy holds. The
        // previous fallback to legacy would also return ZERO here (legacy
        // checks assetBalance > 0), but the contract is now uniform: strict
        // ZERO means the operator gets no surprise full-inventory trades.
        EnrichedStrategyContext ctx = shortCtxWithToggle(
                CASH, /*assetBalance=*/null, new BigDecimal("25"),
                /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal qty = helper.calculateShortEntryQty(ctx, ENTRY, STOP_2PCT_SHORT);
        assertBdEq("0", qty);
    }

    @Test
    void shortEntry_toggleOn_cashZeroReturnsZero_doesNotOverTrade() {
        // Issue #2 from the audit — pre-fix this fell back to legacy which
        // would have returned `assetBalance × allocFraction = 1.0 BTC`,
        // completely ignoring the risk-based intent. New strict semantics
        // return ZERO so the strategy holds when there's no risk capital.
        EnrichedStrategyContext ctx = shortCtxWithToggle(
                BigDecimal.ZERO, new BigDecimal("1"), new BigDecimal("100"),
                /*useRisk=*/true, new BigDecimal("0.05"));
        BigDecimal qty = helper.calculateShortEntryQty(ctx, ENTRY, STOP_2PCT_SHORT);
        assertBdEq("0", qty);
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

    private static EnrichedStrategyContext ctxWithToggle(
            BigDecimal cash, BigDecimal allocPct, boolean useRisk, BigDecimal riskPct) {
        AccountStrategy as = AccountStrategy.builder()
                .strategyCode("LSR")
                .capitalAllocationPct(allocPct)
                .useRiskBasedSizing(useRisk)
                .riskPct(riskPct)
                .build();
        return EnrichedStrategyContext.builder()
                .accountStrategy(as)
                .cashBalance(cash)
                .build();
    }

    private static EnrichedStrategyContext shortCtxWithToggle(
            BigDecimal cash, BigDecimal assetBalance, BigDecimal allocPct,
            boolean useRisk, BigDecimal riskPct) {
        AccountStrategy as = AccountStrategy.builder()
                .strategyCode("LSR")
                .capitalAllocationPct(allocPct)
                .useRiskBasedSizing(useRisk)
                .riskPct(riskPct)
                .build();
        return EnrichedStrategyContext.builder()
                .accountStrategy(as)
                .cashBalance(cash)
                .assetBalance(assetBalance)
                .build();
    }

    private static void assertBdEq(String expected, BigDecimal actual) {
        BigDecimal exp = new BigDecimal(expected);
        assertNotNull(actual,
                () -> "expected " + expected + " but got null");
        assertEquals(0, exp.compareTo(actual),
                () -> "expected " + expected + " but got " + actual);
    }
}
