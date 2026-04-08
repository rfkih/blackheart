package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    public BigDecimal calculateEntryNotional(EnrichedStrategyContext context, String side) {
        if (context == null || side == null || side.isBlank()) {
            return ZERO;
        }

        BigDecimal riskPerTradePct = context.getRiskSnapshot() != null
                ? context.getRiskSnapshot().getFinalRiskPct()
                : null;

        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0) {
            riskPerTradePct = context.getRuntimeConfig() != null
                    ? context.getRuntimeConfig().getRiskPerTradePct()
                    : null;
        }

        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0) {
            riskPerTradePct = context.getAccount() != null
                    ? context.getAccount().getRiskAmount()
                    : null;
        }

        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        String source = resolveExecutionSource(context);

        if (SIDE_LONG.equalsIgnoreCase(side)) {
            BigDecimal cashBalance = context.getCashBalance();
            if (cashBalance == null || cashBalance.compareTo(ZERO) <= 0) {
                return ZERO;
            }

            return cashBalance.multiply(riskPerTradePct).setScale(8, RoundingMode.HALF_UP);
        }

        if (SIDE_SHORT.equalsIgnoreCase(side)) {
            if (SOURCE_LIVE.equalsIgnoreCase(source)) {
                BigDecimal assetBalance = context.getAssetBalance();
                BigDecimal price = context.getMarketData() != null ? context.getMarketData().getClosePrice() : null;

                if (assetBalance == null || assetBalance.compareTo(ZERO) <= 0
                        || price == null || price.compareTo(ZERO) <= 0) {
                    return ZERO;
                }

                BigDecimal sellableNotional = assetBalance.multiply(price);
                return sellableNotional.multiply(riskPerTradePct).setScale(8, RoundingMode.HALF_UP);
            }

            BigDecimal cashBalance = context.getCashBalance();
            if (cashBalance == null || cashBalance.compareTo(ZERO) <= 0) {
                return ZERO;
            }

            return cashBalance.multiply(riskPerTradePct).setScale(8, RoundingMode.HALF_UP);
        }

        return ZERO;
    }

    public BigDecimal calculateShortPositionSize(EnrichedStrategyContext context) {
        BigDecimal riskPerTradePct = resolveRiskPerTradePct(context);
        BigDecimal assetBalance = context.getAssetBalance();

        if (riskPerTradePct == null || riskPerTradePct.compareTo(ZERO) <= 0
                || assetBalance == null || assetBalance.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        return assetBalance.multiply(riskPerTradePct).setScale(8, RoundingMode.HALF_UP);
    }

    public String resolveExecutionSource(EnrichedStrategyContext context) {
        String source = context.getExecutionMetadata("source", String.class);
        if (source == null || source.isBlank()) {
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

    private BigDecimal resolveRiskPerTradePct(EnrichedStrategyContext context) {
        if (context.getRiskSnapshot() != null
                && context.getRiskSnapshot().getFinalRiskPct() != null
                && context.getRiskSnapshot().getFinalRiskPct().compareTo(ZERO) > 0) {
            return context.getRiskSnapshot().getFinalRiskPct();
        }

        if (context.getRuntimeConfig() != null
                && context.getRuntimeConfig().getRiskPerTradePct() != null
                && context.getRuntimeConfig().getRiskPerTradePct().compareTo(ZERO) > 0) {
            return context.getRuntimeConfig().getRiskPerTradePct();
        }

        if (context.getAccount() != null
                && context.getAccount().getRiskAmount() != null
                && context.getAccount().getRiskAmount().compareTo(ZERO) > 0) {
            return context.getAccount().getRiskAmount();
        }

        return null;
    }
}