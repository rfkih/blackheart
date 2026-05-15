package id.co.blackheart.service.statistics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Per-trade return statistics — normalises P&amp;L by the actual USDT notional
 * placed instead of by total capital. The capital-based {@code return_pct}
 * conflates "edge per trade" with "how much of the bankroll was risked";
 * sizing changes can mask a real edge or inflate a fake one. This class
 * exposes the same numbers as if every trade had been sized to a fixed
 * fraction of equity.
 *
 * <p><b>Geometric return at allocation α</b> compounds the per-trade return
 * rate against α of equity. If you size every trade at α × equity:
 * <pre>
 *   r_i  = pnl_i / notional_i              (the realised per-trade return rate)
 *   M(α) = Π (1 + α × r_i)                 (equity multiplier over the series)
 *   geometric_return_pct = (M(α) - 1) × 100
 * </pre>
 *
 * <p>Compounding clamps to zero on ruin (when {@code 1 + α × r_i ≤ 0}). The
 * series stops there — the rest of the trade list is irrelevant because the
 * account is gone.
 */
public final class GeometricReturnCalculator {

    /** Headline allocation used across backtest + live reporting. 90% leaves
     *  a small buffer for fees / slippage, the highest practical value that
     *  doesn't court ruin from any single -100% trade. */
    public static final BigDecimal DEFAULT_ALLOCATION_FRACTION = new BigDecimal("0.90");

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private GeometricReturnCalculator() {}

    /**
     * A single trade for the calculator's purposes — only the pnl (signed
     * USDT) and the notional (USDT placed at entry) matter. Either may be
     * null; nulls are skipped by {@link #compute}.
     */
    public record TradeReturn(BigDecimal pnl, BigDecimal notional) {}

    public record Result(BigDecimal avgTradeReturnPct, BigDecimal geometricReturnPct) {
        public static Result zero() {
            return new Result(BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /**
     * Compute per-trade-return stats over the trade series.
     *
     * @param trades chronological series; order matters for geometric compounding
     * @param allocationFraction α — fraction of equity sized per trade (0–1).
     *                            Use {@link #DEFAULT_ALLOCATION_FRACTION} for the
     *                            standard 90% headline.
     */
    public static Result compute(List<TradeReturn> trades, BigDecimal allocationFraction) {
        if (trades == null || trades.isEmpty()) return Result.zero();
        if (allocationFraction == null || allocationFraction.signum() <= 0) return Result.zero();

        BigDecimal sumReturn = BigDecimal.ZERO;
        BigDecimal multiplier = BigDecimal.ONE;
        int counted = 0;
        boolean ruined = false;

        for (TradeReturn t : trades) {
            BigDecimal r = perTradeReturn(t);
            if (r == null) continue;
            counted++;
            sumReturn = sumReturn.add(r);

            if (!ruined) {
                BigDecimal step = BigDecimal.ONE.add(allocationFraction.multiply(r, MC), MC);
                if (step.signum() <= 0) {
                    multiplier = BigDecimal.ZERO;
                    ruined = true;
                } else {
                    multiplier = multiplier.multiply(step, MC);
                }
            }
        }

        if (counted == 0) return Result.zero();

        BigDecimal avgPct = sumReturn
                .divide(BigDecimal.valueOf(counted), MC)
                .multiply(ONE_HUNDRED, MC)
                .setScale(6, RoundingMode.HALF_UP);

        BigDecimal geoPct = multiplier.subtract(BigDecimal.ONE)
                .multiply(ONE_HUNDRED, MC)
                .setScale(6, RoundingMode.HALF_UP);

        return new Result(avgPct, geoPct);
    }

    /** Convenience overload using {@link #DEFAULT_ALLOCATION_FRACTION}. */
    public static Result compute(List<TradeReturn> trades) {
        return compute(trades, DEFAULT_ALLOCATION_FRACTION);
    }

    private static BigDecimal perTradeReturn(TradeReturn t) {
        if (t == null || t.pnl() == null || t.notional() == null) return null;
        if (t.notional().signum() <= 0) return null;
        return t.pnl().divide(t.notional(), MC);
    }
}
