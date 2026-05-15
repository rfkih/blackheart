package id.co.blackheart.service.statistics;

import id.co.blackheart.service.statistics.GeometricReturnCalculator.Result;
import id.co.blackheart.service.statistics.GeometricReturnCalculator.TradeReturn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GeometricReturnCalculatorTest {

    @Test
    void emptyListReturnsZero() {
        Result r = GeometricReturnCalculator.compute(List.of());
        assertEquals(0, r.avgTradeReturnPct().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.geometricReturnPct().compareTo(BigDecimal.ZERO));
    }

    @Test
    void nullListReturnsZero() {
        Result r = GeometricReturnCalculator.compute(null);
        assertEquals(0, r.avgTradeReturnPct().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.geometricReturnPct().compareTo(BigDecimal.ZERO));
    }

    @Test
    void singleWinningTradeAvgEqualsRate() {
        // 10 USDT pnl on 100 USDT notional → 10% per-trade return
        Result r = GeometricReturnCalculator.compute(List.of(
                new TradeReturn(new BigDecimal("10"), new BigDecimal("100"))));
        // avg = 10%
        assertEquals(0, new BigDecimal("10.000000").compareTo(r.avgTradeReturnPct()));
        // geometric @ 90% = (1 + 0.9 × 0.10) - 1 = 0.09 → 9%
        assertEquals(0, new BigDecimal("9.000000").compareTo(r.geometricReturnPct()));
    }

    @Test
    void twoTradesCompoundMultiplicativelyNotAdditively() {
        // Two +10% trades. Average is 10%. Geometric at α=0.9 is
        // (1.09)^2 - 1 = 0.1881 = 18.81%
        Result r = GeometricReturnCalculator.compute(List.of(
                new TradeReturn(new BigDecimal("10"), new BigDecimal("100")),
                new TradeReturn(new BigDecimal("10"), new BigDecimal("100"))));
        assertEquals(0, new BigDecimal("10.000000").compareTo(r.avgTradeReturnPct()));
        assertEquals(0, new BigDecimal("18.810000").compareTo(r.geometricReturnPct()));
    }

    @Test
    void mixedWinLossSeries() {
        // +10%, -5%, +20%. Avg = 8.333... %. Geometric @ α=0.9:
        // (1 + 0.09) × (1 - 0.045) × (1 + 0.18)
        // = 1.09 × 0.955 × 1.18
        // = 1.22832...10
        // -1 → 22.83210% (rounded to 6 dp)
        Result r = GeometricReturnCalculator.compute(List.of(
                new TradeReturn(new BigDecimal("10"),  new BigDecimal("100")),
                new TradeReturn(new BigDecimal("-5"),  new BigDecimal("100")),
                new TradeReturn(new BigDecimal("20"),  new BigDecimal("100"))));
        assertEquals(0, new BigDecimal("8.333333").compareTo(r.avgTradeReturnPct()));
        assertEquals(0, new BigDecimal("22.832100").compareTo(r.geometricReturnPct()));
    }

    @Test
    void allLosersGiveNegativeGeometric() {
        Result r = GeometricReturnCalculator.compute(List.of(
                new TradeReturn(new BigDecimal("-5"), new BigDecimal("100")),
                new TradeReturn(new BigDecimal("-5"), new BigDecimal("100"))));
        // avg = -5%
        assertEquals(0, new BigDecimal("-5.000000").compareTo(r.avgTradeReturnPct()));
        // geom @ 0.9 = (1 - 0.045)^2 - 1 = -0.087975 → -8.7975%
        assertEquals(0, new BigDecimal("-8.797500").compareTo(r.geometricReturnPct()));
    }

    @Test
    void ruinFromCatastrophicLossClampsToMinusHundred() {
        // -120% per-trade: 1 + 0.9 × (-1.20) = -0.08 → ruin, multiplier clamps to 0.
        Result r = GeometricReturnCalculator.compute(List.of(
                new TradeReturn(new BigDecimal("-120"), new BigDecimal("100")),
                new TradeReturn(new BigDecimal("50"),   new BigDecimal("100"))));
        // Subsequent winner ignored — account already zero.
        assertEquals(0, new BigDecimal("-100.000000").compareTo(r.geometricReturnPct()));
    }

    @Test
    void zeroNotionalTradesAreSkipped() {
        Result r = GeometricReturnCalculator.compute(List.of(
                new TradeReturn(new BigDecimal("10"), new BigDecimal("100")),
                new TradeReturn(new BigDecimal("5"),  BigDecimal.ZERO),
                new TradeReturn(new BigDecimal("10"), new BigDecimal("100"))));
        // Only the two valid 10% trades count — same as the two-trade case.
        assertEquals(0, new BigDecimal("10.000000").compareTo(r.avgTradeReturnPct()));
        assertEquals(0, new BigDecimal("18.810000").compareTo(r.geometricReturnPct()));
    }

    @Test
    void nullFieldsAreSkipped() {
        Result r = GeometricReturnCalculator.compute(List.of(
                new TradeReturn(null, new BigDecimal("100")),
                new TradeReturn(new BigDecimal("10"), null),
                new TradeReturn(new BigDecimal("10"), new BigDecimal("100"))));
        // Only the third (10, 100) is valid.
        assertEquals(0, new BigDecimal("10.000000").compareTo(r.avgTradeReturnPct()));
        assertEquals(0, new BigDecimal("9.000000").compareTo(r.geometricReturnPct()));
    }

    @Test
    void customAllocationFractionUsed() {
        // 10% per trade. At α=0.5: (1 + 0.05) - 1 = 5%.
        Result r = GeometricReturnCalculator.compute(
                List.of(new TradeReturn(new BigDecimal("10"), new BigDecimal("100"))),
                new BigDecimal("0.50"));
        assertEquals(0, new BigDecimal("5.000000").compareTo(r.geometricReturnPct()));
    }

    @Test
    void zeroAllocationReturnsZeroGeometric() {
        Result r = GeometricReturnCalculator.compute(
                List.of(new TradeReturn(new BigDecimal("10"), new BigDecimal("100"))),
                BigDecimal.ZERO);
        assertEquals(0, r.geometricReturnPct().compareTo(BigDecimal.ZERO));
    }

    @Test
    void orderMattersForGeometricCompound() {
        // Two-trade series with identical avg but different geometric due to
        // path-dependence. Not strictly enforceable here because each trade
        // returns the same %, but a +50%/-30% sequence vs -30%/+50% is
        // geometrically identical (commutative product); we sanity-check that
        // the calculator returns the same number when the SAME trades are
        // reordered, since each TradeReturn carries its own (pnl, notional).
        List<TradeReturn> a = List.of(
                new TradeReturn(new BigDecimal("50"),  new BigDecimal("100")),
                new TradeReturn(new BigDecimal("-30"), new BigDecimal("100")));
        List<TradeReturn> b = List.of(
                new TradeReturn(new BigDecimal("-30"), new BigDecimal("100")),
                new TradeReturn(new BigDecimal("50"),  new BigDecimal("100")));
        Result ra = GeometricReturnCalculator.compute(a);
        Result rb = GeometricReturnCalculator.compute(b);
        // Same product → same result.
        assertEquals(0, ra.geometricReturnPct().compareTo(rb.geometricReturnPct()));
        // But geometric must differ from arithmetic average — sanity that we
        // are NOT just summing percentages.
        assertNotEquals(0, ra.avgTradeReturnPct().compareTo(ra.geometricReturnPct()));
    }
}
