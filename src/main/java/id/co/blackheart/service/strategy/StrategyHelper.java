package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Component
public class StrategyHelper {

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";
    private static final String SOURCE_LIVE = "live";
    private static final String SOURCE_BACKTEST = "backtest";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
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
