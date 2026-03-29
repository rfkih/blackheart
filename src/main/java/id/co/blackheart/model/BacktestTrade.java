package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
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

    @Column(name = "strategy_name", length = 100)
    private String strategyName;

    @Column(name = "interval", length = 20)
    private String interval;

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

    @Column(name = "avg_entry_price", precision = 24, scale = 8)
    private BigDecimal avgEntryPrice;

    @Column(name = "avg_exit_price", precision = 24, scale = 8)
    private BigDecimal avgExitPrice;

    @Column(name = "total_entry_qty", precision = 24, scale = 8)
    private BigDecimal totalEntryQty;

    @Column(name = "total_entry_quote_qty", precision = 24, scale = 8)
    private BigDecimal totalEntryQuoteQty;

    @Column(name = "total_remaining_qty", precision = 24, scale = 8)
    private BigDecimal totalRemainingQty;

    @Column(name = "realized_pnl_amount", precision = 24, scale = 8)
    private BigDecimal realizedPnlAmount;

    @Column(name = "realized_pnl_percent", precision = 24, scale = 8)
    private BigDecimal realizedPnlPercent;

    @Column(name = "total_fee_amount", precision = 24, scale = 8)
    private BigDecimal totalFeeAmount;

    @Column(name = "total_fee_currency", length = 20)
    private String totalFeeCurrency;

    @Column(name = "exit_reason", length = 50)
    private String exitReason;

    @Column(name = "entry_trend_regime", length = 50)
    private String entryTrendRegime;

    @Column(name = "entry_adx", precision = 24, scale = 8)
    private BigDecimal entryAdx;

    @Column(name = "entry_atr", precision = 24, scale = 8)
    private BigDecimal entryAtr;

    @Column(name = "entry_rsi", precision = 24, scale = 8)
    private BigDecimal entryRsi;

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @CreationTimestamp
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @UpdateTimestamp
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}