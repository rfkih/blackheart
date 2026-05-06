package id.co.blackheart.service.backtest;

import id.co.blackheart.model.FundingRate;
import id.co.blackheart.repository.FundingRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Funding-cost simulation for the backtest engine.
 *
 * <p>Two paths:
 * <ul>
 *   <li>{@link #compute(BigDecimal, String, LocalDateTime, LocalDateTime,
 *       BigDecimal)} — legacy flat-rate stub. Used when the operator pins
 *       a single {@code funding_rate_bps_per_8h} on the run (tests,
 *       what-if sweeps).</li>
 *   <li>{@link #computePerEvent(BigDecimal, String, String, LocalDateTime,
 *       LocalDateTime)} — Phase 4 default. Sums actual settlement events
 *       from {@code funding_rate_history} between entry and exit. One
 *       charge per event at the rate active at that instant.</li>
 * </ul>
 *
 * <p>Sign convention (both paths):
 * <pre>
 *   rate &gt; 0  → LONG pays,  SHORT receives
 *   rate &lt; 0  → LONG gets,  SHORT pays
 * </pre>
 *
 * <p>Returned value is the cost <em>to the position</em> in quote currency
 * (USDT). Positive = subtract from PnL. Negative = add to PnL (position
 * received funding).
 */
@Service
@RequiredArgsConstructor
public class BacktestFundingCostService {

    private final FundingRateRepository fundingRateRepository;

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

    /**
     * Per-event funding cost — sums every {@code funding_rate_history} event
     * with {@code entryTime < fundingTime <= exitTime}, charging the position
     * its {@code notional × rate} at each settlement instant. Notional is
     * held flat at the position's entry value (the executor already splits
     * partial closes into separate {@code TradePosition} rows, so each call
     * here corresponds to a single immutable leg).
     *
     * <p>Returns {@link BigDecimal#ZERO} when funding history has no rows in
     * the window — same behavior as a cold-start symbol or a non-perp pair.
     */
    public BigDecimal computePerEvent(
            BigDecimal notional,
            String side,
            String symbol,
            LocalDateTime entryTime,
            LocalDateTime exitTime
    ) {
        if (notional == null || notional.signum() <= 0) return BigDecimal.ZERO;
        if (entryTime == null || exitTime == null) return BigDecimal.ZERO;
        if (!exitTime.isAfter(entryTime)) return BigDecimal.ZERO;
        if (side == null || !StringUtils.hasText(symbol)) return BigDecimal.ZERO;

        List<FundingRate> events = fundingRateRepository.findInWindow(symbol, entryTime, exitTime);
        if (CollectionUtils.isEmpty(events)) return BigDecimal.ZERO;

        BigDecimal total = BigDecimal.ZERO;
        for (FundingRate ev : events) {
            BigDecimal eventCost = notional
                    .multiply(ev.getFundingRate())
                    .setScale(SCALE, RoundingMode.HALF_UP);
            total = total.add(eventCost);
        }

        boolean isShort = "SHORT".equalsIgnoreCase(side);
        return isShort ? total.negate() : total;
    }
}
