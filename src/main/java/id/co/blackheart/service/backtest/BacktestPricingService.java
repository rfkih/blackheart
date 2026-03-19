package id.co.blackheart.service.backtest;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BacktestPricingService {

    private static final int PRICE_SCALE = 12;

    public BigDecimal applyEntrySlippage(BigDecimal price, BigDecimal slippageRate, String side) {
        validatePrice(price);

        if (slippageRate == null || slippageRate.compareTo(BigDecimal.ZERO) <= 0) {
            return price.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }

        if ("LONG".equalsIgnoreCase(side)) {
            return price.multiply(BigDecimal.ONE.add(slippageRate))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }

        if ("SHORT".equalsIgnoreCase(side)) {
            return price.multiply(BigDecimal.ONE.subtract(slippageRate))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }

        throw new IllegalArgumentException("Unsupported side for entry slippage: " + side);
    }

    public BigDecimal applyExitSlippage(BigDecimal price, BigDecimal slippageRate, String side) {
        validatePrice(price);

        if (slippageRate == null || slippageRate.compareTo(BigDecimal.ZERO) <= 0) {
            return price.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }

        if ("LONG".equalsIgnoreCase(side)) {
            return price.multiply(BigDecimal.ONE.subtract(slippageRate))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }

        if ("SHORT".equalsIgnoreCase(side)) {
            return price.multiply(BigDecimal.ONE.add(slippageRate))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }

        throw new IllegalArgumentException("Unsupported side for exit slippage: " + side);
    }

    private void validatePrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be greater than zero");
        }
    }
}