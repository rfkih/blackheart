package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backtest_trade")
public class BacktestTrade {

    @Id
    @Column(name = "backtest_trade_id", nullable = false, updatable = false)
    private UUID backtestTradeId;

    @Column(name = "backtest_run_id", nullable = false)
    private UUID backtestRunId;

    @Column(name = "account_strategy_id")
    private UUID accountStrategyId;

    // ─────────────────────────────────────────────────────────────
    // Strategy / market identity
    // ─────────────────────────────────────────────────────────────

    @Column(name = "strategy_code", length = 100)
    private String strategyCode;

    @Column(name = "strategy_name", length = 150)
    private String strategyName;

    @Column(name = "strategy_version", length = 50)
    private String strategyVersion;

    @Column(name = "interval", length = 20)
    private String interval;

    @Column(name = "bias_interval", length = 20)
    private String biasInterval;

    @Column(name = "exchange", length = 30)
    private String exchange;

    @Column(name = "asset", length = 30, nullable = false)
    private String asset;

    @Column(name = "side", length = 10, nullable = false)
    private String side;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "trade_mode", length = 50, nullable = false)
    private String tradeMode;

    @Column(name = "signal_type", length = 100)
    private String signalType;

    @Column(name = "setup_type", length = 100)
    private String setupType;

    @Column(name = "entry_reason", length = 500)
    private String entryReason;

    @Column(name = "exit_reason", length = 100)
    private String exitReason;

    // ─────────────────────────────────────────────────────────────
    // Position sizing / execution
    // ─────────────────────────────────────────────────────────────

    @Column(name = "notional_size", precision = 24, scale = 8)
    private BigDecimal notionalSize;

    @Column(name = "avg_entry_price", precision = 24, scale = 8)
    private BigDecimal avgEntryPrice;

    @Column(name = "avg_exit_price", precision = 24, scale = 8)
    private BigDecimal avgExitPrice;

    @Column(name = "total_entry_qty", precision = 24, scale = 8)
    private BigDecimal totalEntryQty;

    @Column(name = "total_entry_quote_qty", precision = 24, scale = 8)
    private BigDecimal totalEntryQuoteQty;

    @Column(name = "total_exit_qty", precision = 24, scale = 8)
    private BigDecimal totalExitQty;

    @Column(name = "total_exit_quote_qty", precision = 24, scale = 8)
    private BigDecimal totalExitQuoteQty;

    @Column(name = "total_remaining_qty", precision = 24, scale = 8)
    private BigDecimal totalRemainingQty;

    @Column(name = "slippage_amount", precision = 24, scale = 8)
    private BigDecimal slippageAmount;

    @Column(name = "slippage_percent", precision = 24, scale = 8)
    private BigDecimal slippagePercent;

    // ─────────────────────────────────────────────────────────────
    // Risk at entry
    // ─────────────────────────────────────────────────────────────

    @Column(name = "initial_stop_loss_price", precision = 24, scale = 8)
    private BigDecimal initialStopLossPrice;

    @Column(name = "final_stop_loss_price", precision = 24, scale = 8)
    private BigDecimal finalStopLossPrice;

    @Column(name = "initial_trailing_stop_price", precision = 24, scale = 8)
    private BigDecimal initialTrailingStopPrice;

    @Column(name = "last_trailing_stop_price", precision = 24, scale = 8)
    private BigDecimal lastTrailingStopPrice;

    @Column(name = "initial_risk_per_unit", precision = 24, scale = 8)
    private BigDecimal initialRiskPerUnit;

    @Column(name = "initial_risk_amount", precision = 24, scale = 8)
    private BigDecimal initialRiskAmount;

    @Column(name = "initial_risk_percent", precision = 24, scale = 8)
    private BigDecimal initialRiskPercent;

    // ─────────────────────────────────────────────────────────────
    // Realized result
    // ─────────────────────────────────────────────────────────────

    @Column(name = "gross_pnl_amount", precision = 24, scale = 8)
    private BigDecimal grossPnlAmount;

    @Column(name = "realized_pnl_amount", precision = 24, scale = 8)
    private BigDecimal realizedPnlAmount;

    @Column(name = "realized_pnl_percent", precision = 24, scale = 8)
    private BigDecimal realizedPnlPercent;

    @Column(name = "realized_r_multiple", precision = 24, scale = 8)
    private BigDecimal realizedRMultiple;

    @Column(name = "total_fee_amount", precision = 24, scale = 8)
    private BigDecimal totalFeeAmount;

    @Column(name = "total_fee_currency", length = 20)
    private String totalFeeCurrency;

    // ─────────────────────────────────────────────────────────────
    // Trade path analytics
    // ─────────────────────────────────────────────────────────────

    @Column(name = "highest_price_during_trade", precision = 24, scale = 8)
    private BigDecimal highestPriceDuringTrade;

    @Column(name = "lowest_price_during_trade", precision = 24, scale = 8)
    private BigDecimal lowestPriceDuringTrade;

    @Column(name = "max_favorable_excursion_amount", precision = 24, scale = 8)
    private BigDecimal maxFavorableExcursionAmount;

    @Column(name = "max_adverse_excursion_amount", precision = 24, scale = 8)
    private BigDecimal maxAdverseExcursionAmount;

    @Column(name = "max_favorable_excursion_r", precision = 24, scale = 8)
    private BigDecimal maxFavorableExcursionR;

    @Column(name = "max_adverse_excursion_r", precision = 24, scale = 8)
    private BigDecimal maxAdverseExcursionR;

    // ─────────────────────────────────────────────────────────────
    // Entry snapshot: execution timeframe
    // ─────────────────────────────────────────────────────────────

    @Column(name = "entry_trend_regime", length = 50)
    private String entryTrendRegime;

    @Column(name = "entry_signal_score", precision = 24, scale = 8)
    private BigDecimal entrySignalScore;

    @Column(name = "entry_confidence_score", precision = 24, scale = 8)
    private BigDecimal entryConfidenceScore;

    @Column(name = "entry_adx", precision = 24, scale = 8)
    private BigDecimal entryAdx;

    @Column(name = "entry_atr", precision = 24, scale = 8)
    private BigDecimal entryAtr;

    @Column(name = "entry_rsi", precision = 24, scale = 8)
    private BigDecimal entryRsi;

    @Column(name = "entry_macd_histogram", precision = 24, scale = 8)
    private BigDecimal entryMacdHistogram;

    @Column(name = "entry_signed_er20", precision = 24, scale = 8)
    private BigDecimal entrySignedEr20;

    @Column(name = "entry_relative_volume20", precision = 24, scale = 8)
    private BigDecimal entryRelativeVolume20;

    @Column(name = "entry_plus_di", precision = 24, scale = 8)
    private BigDecimal entryPlusDi;

    @Column(name = "entry_minus_di", precision = 24, scale = 8)
    private BigDecimal entryMinusDi;

    @Column(name = "entry_ema20", precision = 24, scale = 8)
    private BigDecimal entryEma20;

    @Column(name = "entry_ema50", precision = 24, scale = 8)
    private BigDecimal entryEma50;

    @Column(name = "entry_ema200", precision = 24, scale = 8)
    private BigDecimal entryEma200;

    @Column(name = "entry_ema50_slope", precision = 24, scale = 8)
    private BigDecimal entryEma50Slope;

    @Column(name = "entry_ema200_slope", precision = 24, scale = 8)
    private BigDecimal entryEma200Slope;

    @Column(name = "entry_close_location_value", precision = 24, scale = 8)
    private BigDecimal entryCloseLocationValue;

    @Column(name = "entry_is_bullish_breakout")
    private Boolean entryIsBullishBreakout;

    @Column(name = "entry_is_bearish_breakout")
    private Boolean entryIsBearishBreakout;

    // ─────────────────────────────────────────────────────────────
    // Entry snapshot: higher timeframe bias
    // ─────────────────────────────────────────────────────────────

    @Column(name = "bias_trend_regime", length = 50)
    private String biasTrendRegime;

    @Column(name = "bias_adx", precision = 24, scale = 8)
    private BigDecimal biasAdx;

    @Column(name = "bias_atr", precision = 24, scale = 8)
    private BigDecimal biasAtr;

    @Column(name = "bias_rsi", precision = 24, scale = 8)
    private BigDecimal biasRsi;

    @Column(name = "bias_macd_histogram", precision = 24, scale = 8)
    private BigDecimal biasMacdHistogram;

    @Column(name = "bias_signed_er20", precision = 24, scale = 8)
    private BigDecimal biasSignedEr20;

    @Column(name = "bias_plus_di", precision = 24, scale = 8)
    private BigDecimal biasPlusDi;

    @Column(name = "bias_minus_di", precision = 24, scale = 8)
    private BigDecimal biasMinusDi;

    @Column(name = "bias_ema50", precision = 24, scale = 8)
    private BigDecimal biasEma50;

    @Column(name = "bias_ema200", precision = 24, scale = 8)
    private BigDecimal biasEma200;

    @Column(name = "bias_ema200_slope", precision = 24, scale = 8)
    private BigDecimal biasEma200Slope;

    // ─────────────────────────────────────────────────────────────
    // Time / duration
    // ─────────────────────────────────────────────────────────────

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @Column(name = "holding_minutes")
    private Long holdingMinutes;

    @Column(name = "bars_held")
    private Integer barsHeld;

    @CreationTimestamp
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @UpdateTimestamp
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;

}