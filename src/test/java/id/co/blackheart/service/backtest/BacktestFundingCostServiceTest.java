package id.co.blackheart.service.backtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the funding-cost stub math (V22): rate × hold-fraction × notional,
 * signed so LONG pays + / SHORT receives + when rate &gt; 0. Default rate of
 * zero (or null) must return zero so legacy backtests stay bit-identical.
 */
class BacktestFundingCostServiceTest {

    private BacktestFundingCostService service;

    @BeforeEach
    void setUp() {
        // Per-event lookup is exercised separately; the legacy compute() path
        // never touches the repository, so a null arg is safe here.
        service = new BacktestFundingCostService(null);
    }

    @Test
    void zeroRateReturnsZero() {
        BigDecimal cost = service.compute(
                new BigDecimal("10000"),
                "LONG",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                BigDecimal.ZERO);
        assertEquals(0, cost.signum());
    }

    @Test
    void nullRateReturnsZero() {
        BigDecimal cost = service.compute(
                new BigDecimal("10000"),
                "LONG",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                null);
        assertEquals(0, cost.signum());
    }

    @Test
    void longPaysPositiveRate() {
        // 10_000 USDT × 1 bps × (24h / 8h) = 10_000 × 0.0001 × 3 = 3.0
        BigDecimal cost = service.compute(
                new BigDecimal("10000"),
                "LONG",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                new BigDecimal("1.0"));
        assertEquals(0, cost.compareTo(new BigDecimal("3.00000000")),
                "expected 3.0 USDT, got " + cost);
    }

    @Test
    void shortReceivesPositiveRate() {
        // Same magnitude as long-paying, but sign flipped (negative = credit)
        BigDecimal cost = service.compute(
                new BigDecimal("10000"),
                "SHORT",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                new BigDecimal("1.0"));
        assertEquals(0, cost.compareTo(new BigDecimal("-3.00000000")),
                "expected -3.0 USDT, got " + cost);
    }

    @Test
    void longReceivesNegativeRate() {
        // Inverted funding (rare): long collects, short pays
        BigDecimal cost = service.compute(
                new BigDecimal("10000"),
                "LONG",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                new BigDecimal("-1.0"));
        assertTrue(cost.signum() < 0, "long with negative rate should be a credit");
    }

    @Test
    void zeroHoldDurationReturnsZero() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        BigDecimal cost = service.compute(
                new BigDecimal("10000"), "LONG", t, t, new BigDecimal("1.0"));
        assertEquals(0, cost.signum());
    }

    @Test
    void exitBeforeEntryReturnsZero() {
        BigDecimal cost = service.compute(
                new BigDecimal("10000"),
                "LONG",
                LocalDateTime.of(2026, 1, 2, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                new BigDecimal("1.0"));
        assertEquals(0, cost.signum());
    }

    @Test
    void subPeriodHoldScalesProportionally() {
        // 4h hold = half a funding period → half the cost of an 8h hold
        BigDecimal cost4h = service.compute(
                new BigDecimal("10000"),
                "LONG",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 4, 0),
                new BigDecimal("1.0"));
        // 10_000 × 0.0001 × 0.5 = 0.5
        assertEquals(0, cost4h.compareTo(new BigDecimal("0.50000000")),
                "expected 0.5 USDT for 4h LONG, got " + cost4h);
    }
}
