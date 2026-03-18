package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(
        name = "feature_store",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"symbol", "interval", "start_time"})
        }
)
public class FeatureStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_market_data", nullable = false)
    private Long idMarketData;

    @Column(name = "symbol", length = 20, nullable = false)
    private String symbol;

    @Column(name = "interval", length = 10, nullable = false)
    private String interval;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "price", precision = 24, scale = 8, nullable = false)
    private BigDecimal price;

    // Trend
    @Column(name = "ema_20", precision = 24, scale = 8)
    private BigDecimal ema20;

    @Column(name = "ema_50", precision = 24, scale = 8)
    private BigDecimal ema50;

    @Column(name = "ema_200", precision = 24, scale = 8)
    private BigDecimal ema200;

    @Column(name = "ema_50_slope", precision = 24, scale = 8)
    private BigDecimal ema50Slope;

    @Column(name = "ema_200_slope", precision = 24, scale = 8)
    private BigDecimal ema200Slope;

    // Trend strength
    @Column(name = "adx", precision = 24, scale = 8)
    private BigDecimal adx;

    @Column(name = "plus_di", precision = 24, scale = 8)
    private BigDecimal plusDI;

    @Column(name = "minus_di", precision = 24, scale = 8)
    private BigDecimal minusDI;

    @Column(name = "efficiency_ratio_20", precision = 24, scale = 8)
    private BigDecimal efficiencyRatio20;

    // Volatility
    @Column(name = "atr", precision = 24, scale = 8)
    private BigDecimal atr;

    @Column(name = "atr_pct", precision = 24, scale = 8)
    private BigDecimal atrPct;

    // Momentum
    @Column(name = "macd", precision = 24, scale = 8)
    private BigDecimal macd;

    @Column(name = "macd_signal", precision = 24, scale = 8)
    private BigDecimal macdSignal;

    @Column(name = "macd_histogram", precision = 24, scale = 8)
    private BigDecimal macdHistogram;

    @Column(name = "rsi", precision = 24, scale = 8)
    private BigDecimal rsi;

    // Structure / breakout
    @Column(name = "donchian_upper_20", precision = 24, scale = 8)
    private BigDecimal donchianUpper20;

    @Column(name = "donchian_lower_20", precision = 24, scale = 8)
    private BigDecimal donchianLower20;

    @Column(name = "donchian_mid_20", precision = 24, scale = 8)
    private BigDecimal donchianMid20;

    @Column(name = "highest_high_20", precision = 24, scale = 8)
    private BigDecimal highestHigh20;

    @Column(name = "lowest_low_20", precision = 24, scale = 8)
    private BigDecimal lowestLow20;

    // Candle quality
    @Column(name = "body_size", precision = 24, scale = 8)
    private BigDecimal bodySize;

    @Column(name = "candle_range", precision = 24, scale = 8)
    private BigDecimal candleRange;

    @Column(name = "body_to_range_ratio", precision = 24, scale = 8)
    private BigDecimal bodyToRangeRatio;

    @Column(name = "close_location_value", precision = 24, scale = 8)
    private BigDecimal closeLocationValue;

    @Column(name = "relative_volume_20", precision = 24, scale = 8)
    private BigDecimal relativeVolume20;

    // Regime summary
    @Column(name = "trend_score")
    private Integer trendScore;

    @Column(name = "trend_regime", length = 10)
    private String trendRegime;

    @Column(name = "volatility_regime", length = 10)
    private String volatilityRegime;

    @Column(name = "is_bullish_breakout")
    private Boolean isBullishBreakout;

    @Column(name = "is_bearish_breakout")
    private Boolean isBearishBreakout;

    @Column(name = "is_bullish_pullback")
    private Boolean isBullishPullback;

    @Column(name = "is_bearish_pullback")
    private Boolean isBearishPullback;

    @Column(name = "entry_bias", length = 10)
    private String entryBias;

    @Column(name = "created_time", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdTime;

    @Column(name = "updated_time")
    @UpdateTimestamp
    private Instant updatedTime;
}