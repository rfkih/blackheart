package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class StrategyHelper {

    /** Latch — log the leverage-clamp warning once per JVM lifetime. We don't
     *  want this firing per tick across a 100-cell sweep with maxAlloc=5.0;
     *  one WARN at first occurrence is enough for the operator to notice. */
    private static final AtomicBoolean LEVERAGE_CLAMP_WARNED = new AtomicBoolean(false);

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";
    private static final String SOURCE_LIVE = "live";
    private static final String SOURCE_BACKTEST = "backtest";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * Trade-amount sizing. {@code AccountStrategy.capitalAllocationPct} is
     * the single sizing knob: at 50% allocation a 100 USDT account places a
     * 50 USDT trade. No further per-trade-risk multiplier is applied here —
     * the per-trade-risk concept lives in the strategy's stop-distance math
     * (R-multiple sizing) when relevant, not in the trade-amount derivation.
     *
     * <p>Returns ZERO when no allocation is set; the executor's "invalid
     * trade amount" check then skips the entry. capital_allocation_pct is
     * the canonical sizing field — a strategy without it doesn't trade.
     */
    public BigDecimal calculateEntryNotional(EnrichedStrategyContext context, String side) {
        if (context == null || !StringUtils.hasText(side)) {
            return ZERO;
        }

        BigDecimal allocFraction = resolveCapitalAllocationFraction(context);
        if (allocFraction.signum() <= 0) return ZERO;

        String source = resolveExecutionSource(context);

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            BigDecimal cashBalance = context.getCashBalance();
            if (cashBalance == null || cashBalance.compareTo(ZERO) <= 0) {
                return ZERO;
            }
            return cashBalance.multiply(allocFraction).setScale(8, RoundingMode.HALF_UP);
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            if (SOURCE_LIVE.equalsIgnoreCase(source)) {
                BigDecimal assetBalance = context.getAssetBalance();
                BigDecimal price = context.getMarketData() != null
                        ? context.getMarketData().getClosePrice() : null;

                if (assetBalance == null || assetBalance.compareTo(ZERO) <= 0
                        || price == null || price.compareTo(ZERO) <= 0) {
                    return ZERO;
                }
                // Notional in USDT for the SHORT side — sellable BTC × price
                // × allocation.
                return assetBalance.multiply(price).multiply(allocFraction)
                        .setScale(8, RoundingMode.HALF_UP);
            }

            BigDecimal cashBalance = context.getCashBalance();
            if (cashBalance == null || cashBalance.compareTo(ZERO) <= 0) {
                return ZERO;
            }
            return cashBalance.multiply(allocFraction).setScale(8, RoundingMode.HALF_UP);
        }

        return ZERO;
    }

    /**
     * SHORT position size in BASE currency (BTC qty). Same single-knob
     * sizing as {@link #calculateEntryNotional}: {@code assetBalance ×
     * allocFraction}. Strategies that need per-trade-risk-aware sizing
     * (e.g. R-based qty from stop distance) compute that on top of this
     * base; this helper just returns the strategy's allocated slice.
     */
    public BigDecimal calculateShortPositionSize(EnrichedStrategyContext context) {
        BigDecimal allocFraction = resolveCapitalAllocationFraction(context);
        if (allocFraction.signum() <= 0) return ZERO;

        BigDecimal assetBalance = context.getAssetBalance();
        if (assetBalance == null || assetBalance.compareTo(ZERO) <= 0) return ZERO;

        return assetBalance.multiply(allocFraction).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Risk-based USDT notional. Sizes the position so the loss when price
     * reaches {@code stopLossPrice} equals approximately
     * {@code riskPct × cashBalance}, then caps at
     * {@code maxAllocationPct × cashBalance} so the trade still executes
     * when the ideal notional would exceed available balance.
     *
     * <p>Concretely:
     * <pre>
     *   stopDistancePct = |entry − stop| / entry
     *   ideal           = cashBalance × riskPct / stopDistancePct
     *   cap             = cashBalance × min(maxAllocationPct, 1.0)
     *   notional        = min(ideal, cap)
     * </pre>
     *
     * <p>Returns ZERO when:
     * <ul>
     *   <li>{@code riskPct} is null or non-positive — caller should fall back
     *       to {@link #calculateEntryNotional} (legacy notional sizing).</li>
     *   <li>Entry, stop, or stop-distance are non-positive (degenerate setup).</li>
     *   <li>{@code cashBalance} is non-positive (no capital to risk).</li>
     * </ul>
     *
     * <p>When {@code maxAllocationPct} is null/non-positive, the existing
     * {@code account_strategy.capital_allocation_pct} (capped at 100%) is
     * used as the cap fraction — preserves the balance-safe behaviour for
     * accounts that haven't opted into a separate cap value.
     *
     * <p><b>Hard cap at 1.0:</b> the backtest engine is cash-1× and the spot
     * executors check the actual balance, so a notional above {@code cashBalance}
     * would just fail the balance check downstream — no leverage is gained.
     * Inputs above 1.0 are silently clamped here. When futures-leverage
     * support lands in the backtest engine, lift this clamp.
     *
     * <p><b>SHORT-on-spot caveat:</b> the cap is computed from {@code cashBalance}
     * (USDT). For futures and backtest this is correct — USDT is the margin
     * currency. For LIVE SPOT SHORT, the executor sells from
     * {@code assetBalance} (BTC) and would reject a USDT-sized notional that
     * doesn't translate back to a coin balance the user actually owns.
     * Strategies that may run on spot live should leave {@code riskPct} null
     * for the SHORT path, or the caller should size against
     * {@code assetBalance × entryPrice} explicitly.
     */
    public BigDecimal calculateRiskBasedNotional(
            EnrichedStrategyContext context,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal riskPct,
            BigDecimal maxAllocationPct) {
        if (context == null) return ZERO;
        if (riskPct == null || riskPct.signum() <= 0) return ZERO;
        if (entryPrice == null || entryPrice.signum() <= 0) return ZERO;
        if (stopLossPrice == null || stopLossPrice.signum() <= 0) return ZERO;

        BigDecimal stopDistance = entryPrice.subtract(stopLossPrice).abs();
        if (stopDistance.signum() <= 0) return ZERO;

        BigDecimal stopDistancePct = stopDistance.divide(entryPrice, 8, RoundingMode.HALF_UP);
        if (stopDistancePct.signum() <= 0) return ZERO;

        BigDecimal capital = safe(context.getCashBalance());
        if (capital.signum() <= 0) return ZERO;

        BigDecimal idealNotional = capital.multiply(riskPct)
                .divide(stopDistancePct, 8, RoundingMode.HALF_UP);

        BigDecimal capFraction = (maxAllocationPct != null && maxAllocationPct.signum() > 0)
                ? maxAllocationPct
                : resolveCapitalAllocationFraction(context);
        if (capFraction.signum() <= 0) return ZERO;
        // Backtest is cash-1×; spot executors balance-check against
        // cashBalance. Allowing >1.0 yields no leverage, just a downstream
        // rejection. Clamp + warn once so the operator notices a misconfigured
        // sweep that's silently flat-lining at the cap.
        if (capFraction.compareTo(ONE) > 0) {
            if (LEVERAGE_CLAMP_WARNED.compareAndSet(false, true)) {
                log.warn("Risk-based sizing: maxAllocationPct={} clamped to 1.0 — "
                        + "backtest engine is cash-1× (no leverage). Sweeps over [1.0, N] "
                        + "will produce identical results above 1.0 until futures-leverage "
                        + "support lands. Suppressing further occurrences.", capFraction);
            }
            capFraction = ONE;
        }

        BigDecimal cap = capital.multiply(capFraction);
        return idealNotional.min(cap).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Returns the strategy's allocation as a fraction in (0, 1]. Reads
     * {@code AccountStrategy.capitalAllocationPct} (stored on the 0–100
     * scale per the schema) and divides by 100; caps the input at 100.
     *
     * <p>Returns ZERO when allocation is null, missing, or non-positive —
     * strict semantics: an unallocated strategy doesn't trade. The schema
     * has the column NOT NULL so this should only happen for partial
     * legacy rows or ad-hoc test contexts.
     */
    private BigDecimal resolveCapitalAllocationFraction(EnrichedStrategyContext context) {
        if (context == null || context.getAccountStrategy() == null) return ZERO;
        BigDecimal alloc = context.getAccountStrategy().getCapitalAllocationPct();
        if (alloc == null || alloc.signum() <= 0) return ZERO;
        BigDecimal capped = alloc.min(ONE_HUNDRED);
        return capped.divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);
    }

    public String resolveExecutionSource(EnrichedStrategyContext context) {
        String source = context.getExecutionMetadata("source", String.class);
        if (!StringUtils.hasText(source)) {
            return SOURCE_BACKTEST;
        }
        return source;
    }

    public boolean hasValue(BigDecimal value) {
        return value != null;
    }

    public BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}
