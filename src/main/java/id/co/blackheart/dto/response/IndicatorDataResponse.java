package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Per-candle technical indicator snapshot for the frontend market chart.
 * Nullable fields pass through as null rather than zero so the chart can skip
 * them instead of drawing a zero-line that pins the scale.
 *
 * <p>BB / KC middle bands aren't stored directly on FeatureStore; the controller
 * derives them as the arithmetic midpoint of the upper/lower bands, which matches
 * what the chart overlays expect visually.
 */
@Data
@Builder
public class IndicatorDataResponse {

    private Long time;

    // Trend
    private BigDecimal ema20;
    private BigDecimal ema50;
    private BigDecimal ema200;

    // Bollinger Bands
    private BigDecimal bbUpper;
    private BigDecimal bbMiddle;
    private BigDecimal bbLower;

    // Keltner Channels
    private BigDecimal kcUpper;
    private BigDecimal kcMiddle;
    private BigDecimal kcLower;

    // Momentum
    private BigDecimal rsi;
    private BigDecimal macd;
    private BigDecimal macdSignal;
    private BigDecimal macdHistogram;

    // Volatility / trend strength
    private BigDecimal atr;
    private BigDecimal adx;
}
