package id.co.blackheart.service.backtest;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Phase 0 stub for funding-cost simulation in the backtest engine.
 *
 * <p>Computes a flat funding charge for a closed position based on the
 * run-level {@code funding_rate_bps_per_8h} and the position's hold
 * duration. Signed so the executor can subtract the result from realised
 * PnL on either side of the book.
 *
 * <p>Sign convention:
 * <pre>
 *   rate &gt; 0  → LONG pays,  SHORT receives
 *   rate &lt; 0  → LONG gets,  SHORT pays
 * </pre>
 *
 * <p>The returned value is the cost <em>to the position</em> in quote
 * currency (USDT). Positive return = subtract from PnL. Negative return
 * = add to PnL (position received funding).
 *
 * <p>Phase 4 will replace this with a per-bar lookup against
 * {@code funding_rate_history}; the run-level stub remains as a fallback
 * for older runs and tests.
 */
@Service
public class BacktestFundingCostService {

    private static final BigDecimal HOURS_PER_FUNDING_PERIOD = new BigDecimal("8");
    private static final BigDecimal BPS_DIVISOR = new BigDecimal("10000");
    private static final int SCALE = 8;

    /**
     * Compute the funding cost for a closed position. Returns
     * {@link BigDecimal#ZERO} when the rate is null/zero or any input is
     * invalid — this is a stub, never a hard fail.
     *
     * @param notional    position notional in quote currency at entry
     *                    (typically {@code entryQty × entryPrice})
     * @param side        {@code "LONG"} or {@code "SHORT"} (case-insensitive)
     * @param entryTime   position entry time
     * @param exitTime    position exit time
     * @param ratePerBpsPer8h   funding rate, basis points per 8h period
     * @return funding cost in quote currency, signed (positive = cost)
     */
    public BigDecimal compute(
            BigDecimal notional,
            String side,
            LocalDateTime entryTime,
            LocalDateTime exitTime,
            BigDecimal ratePerBpsPer8h
    ) {
        if (ratePerBpsPer8h == null || ratePerBpsPer8h.signum() == 0) return BigDecimal.ZERO;
        if (notional == null || notional.signum() <= 0) return BigDecimal.ZERO;
        if (entryTime == null || exitTime == null) return BigDecimal.ZERO;
        if (!exitTime.isAfter(entryTime)) return BigDecimal.ZERO;
        if (side == null) return BigDecimal.ZERO;

        long holdSeconds = Duration.between(entryTime, exitTime).getSeconds();
        BigDecimal holdHours = BigDecimal.valueOf(holdSeconds)
                .divide(BigDecimal.valueOf(3600), SCALE, RoundingMode.HALF_UP);

        BigDecimal periods = holdHours.divide(HOURS_PER_FUNDING_PERIOD, SCALE, RoundingMode.HALF_UP);

        BigDecimal magnitude = notional
                .multiply(ratePerBpsPer8h)
                .multiply(periods)
                .divide(BPS_DIVISOR, SCALE, RoundingMode.HALF_UP);

        boolean isShort = "SHORT".equalsIgnoreCase(side);
        return isShort ? magnitude.negate() : magnitude;
    }
}
