package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class SentimentResponse {

    /** e.g. "BTCUSDT" */
    private String symbol;

    /** Combined label derived from 1h + 4h scores: STRONG_BUY | BUY | NEUTRAL | SELL | STRONG_SELL */
    private String sentiment;

    /**
     * Composite score in [-1.0, 1.0].
     * Weighted combination: 4h score × 0.60 + 1h score × 0.40.
     */
    private BigDecimal score;

    /** Individual 1h directional score in [-1.0, 1.0] */
    private BigDecimal score1h;

    /** Individual 4h directional score in [-1.0, 1.0] */
    private BigDecimal score4h;

    /** 1h label: STRONG_BUY | BUY | NEUTRAL | SELL | STRONG_SELL */
    private String sentiment1h;

    /** 4h label: STRONG_BUY | BUY | NEUTRAL | SELL | STRONG_SELL */
    private String sentiment4h;

    /** Signals from 1h analysis */
    private List<String> signals1h;

    /** Signals from 4h analysis */
    private List<String> signals4h;

    // ── 1h indicator snapshot ─────────────────────────────────────────────────
    private BigDecimal price;
    private BigDecimal rsi1h;
    private BigDecimal adx1h;
    private BigDecimal macdHistogram1h;
    private BigDecimal relativeVolume1h;
    private String trendRegime1h;
    private Boolean isBullishBreakout1h;
    private Boolean isBearishBreakout1h;

    // ── 4h indicator snapshot ─────────────────────────────────────────────────
    private BigDecimal rsi4h;
    private BigDecimal adx4h;
    private BigDecimal macdHistogram4h;
    private String trendRegime4h;
    private String entryBias4h;

    /** When the 1h FeatureStore candle was computed */
    private LocalDateTime featureTime1h;

    /** When the 4h FeatureStore candle was computed */
    private LocalDateTime featureTime4h;

    /** When this sentiment payload was generated */
    private LocalDateTime publishedAt;
}
