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
@Table(name = "backtest_trade_position")
public class BacktestTradePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trade_position_id", nullable = false, updatable = false)
    private UUID tradePositionId;

    @Column(name = "backtest_trade_id", nullable = false)
    private UUID backtestTradeId;

    @Column(name = "backtest_run_id", nullable = false)
    private UUID backtestRunId;

    @Column(name = "account_strategy_id")
    private UUID accountStrategyId;

    @Column(name = "asset", length = 30, nullable = false)
    private String asset;

    @Column(name = "interval", length = 20)
    private String interval;

    @Column(name = "exchange", length = 30)
    private String exchange;

    @Column(name = "side", length = 10, nullable = false)
    private String side;

    @Column(name = "position_role", length = 30, nullable = false)
    private String positionRole;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "entry_price", precision = 24, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "entry_qty", precision = 24, scale = 8)
    private BigDecimal entryQty;

    @Column(name = "entry_quote_qty", precision = 24, scale = 8)
    private BigDecimal entryQuoteQty;

    @Column(name = "remaining_qty", precision = 24, scale = 8)
    private BigDecimal remainingQty;

    @Column(name = "exit_price", precision = 24, scale = 8)
    private BigDecimal exitPrice;

    @Column(name = "exit_executed_qty", precision = 24, scale = 8)
    private BigDecimal exitExecutedQty;

    @Column(name = "exit_executed_quote_qty", precision = 24, scale = 8)
    private BigDecimal exitExecutedQuoteQty;

    @Column(name = "entry_fee", precision = 24, scale = 8)
    private BigDecimal entryFee;

    @Column(name = "entry_fee_currency", length = 20)
    private String entryFeeCurrency;

    @Column(name = "exit_fee", precision = 24, scale = 8)
    private BigDecimal exitFee;

    @Column(name = "exit_fee_currency", length = 20)
    private String exitFeeCurrency;

    @Column(name = "initial_stop_loss_price", precision = 24, scale = 8)
    private BigDecimal initialStopLossPrice;

    @Column(name = "current_stop_loss_price", precision = 24, scale = 8)
    private BigDecimal currentStopLossPrice;

    @Column(name = "trailing_stop_price", precision = 24, scale = 8)
    private BigDecimal trailingStopPrice;

    /**
     * Initial trailing stop price recorded at entry — used to compute the fixed trailing offset
     * during backtest execution. Not persisted (runtime only).
     */
    @Transient
    private BigDecimal initialTrailingStopPrice;

    @Column(name = "take_profit_price", precision = 24, scale = 8)
    private BigDecimal takeProfitPrice;

    @Column(name = "highest_price_since_entry", precision = 24, scale = 8)
    private BigDecimal highestPriceSinceEntry;

    @Column(name = "lowest_price_since_entry", precision = 24, scale = 8)
    private BigDecimal lowestPriceSinceEntry;

    @Column(name = "realized_pnl_amount", precision = 24, scale = 8)
    private BigDecimal realizedPnlAmount;

    @Column(name = "realized_pnl_percent", precision = 24, scale = 8)
    private BigDecimal realizedPnlPercent;

    @Column(name = "exit_reason", length = 50)
    private String exitReason;

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