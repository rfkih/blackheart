package id.co.blackheart.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "feature_store", uniqueConstraints = {@UniqueConstraint(columnNames = {"symbol", "timestamp"})})
public class FeatureStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_market_data", nullable = false)
    private Long idMarketData;

    @Column(name = "symbol", length = 20, nullable = false)
    private String symbol;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    // Price & Moving Averages
    @Column(name = "price", precision = 20, scale = 8, nullable = false)
    private BigDecimal price;

    @Column(name = "sma_14", precision = 20, scale = 8)
    private BigDecimal sma14;

    @Column(name = "sma_50)", precision = 20, scale = 8)
    private BigDecimal sma50;

    @Column(name = "wma", precision = 20, scale = 8)
    private BigDecimal wma;

    // Momentum Indicators
    @Column(name = "momentum", precision = 20, scale = 8)
    private BigDecimal momentum;

    @Column(name = "stoch_k", precision = 20, scale = 8)
    private BigDecimal stochK;

    @Column(name = "stoch_d", precision = 20, scale = 8)
    private BigDecimal stochD;

    // MACD (Moving Average Convergence Divergence)
    @Column(name = "macd", precision = 20, scale = 8)
    private BigDecimal macd;

    @Column(name = "macd_signal", precision = 20, scale = 8)
    private BigDecimal macdSignal;

    @Column(name = "macd_histogram", precision = 20, scale = 8)
    private BigDecimal macdHistogram;

    // RSI, CCI, Williams %R
    @Column(name = "rsi", precision = 5, scale = 2)
    private BigDecimal rsi;

    @Column(name = "williams_r", precision = 20, scale = 8)
    private BigDecimal williamsR;

    @Column(name = "cci", precision = 20, scale = 8)
    private BigDecimal cci;

    // Accumulation/Distribution Oscillator
    @Column(name = "ad_oscillator", precision = 20, scale = 8)
    private BigDecimal adOscillator;

    //ATR (Average True Range)
    @Column(name = "atr", precision = 20, scale = 8)
    private BigDecimal atr;

    @Column(name = "bollinger_upper", precision = 20, scale = 8)
    private BigDecimal bollingerUpper;

    @Column(name = "bollinger_lower", precision = 20, scale = 8)
    private BigDecimal bollingerLower;

    @Column(name = "bollinger_middle", precision = 20, scale = 8)
    private BigDecimal bollingerMiddle;

    @Column(name = "adx", precision = 20, scale = 8)
    private BigDecimal adx;

    @Column(name = "plus_di", precision = 20, scale = 8)
    private BigDecimal plusDI;

    @Column(name = "minus_di", precision = 20, scale = 8)
    private BigDecimal minusDI;

    //VWAP (Volume Weighted Average Price)
    @Column(name = "vwap", precision = 20, scale = 8)
    private BigDecimal vwap;

    @Column(name = "ema_9", precision = 20, scale = 8)
    private BigDecimal ema9;

    @Column(name = "ema_14", precision = 20, scale = 8)
    private BigDecimal ema14;

    @Column(name = "ema_21", precision = 20, scale = 8)
    private BigDecimal ema21;

    @Column(name = "ema_50", precision = 20, scale = 8)
    private BigDecimal ema50;

    @Column(name = "ema_100", precision = 20, scale = 8)
    private BigDecimal ema100;



}
