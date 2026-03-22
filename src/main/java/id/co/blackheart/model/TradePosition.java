package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trade_positions")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TradePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trade_position_id", nullable = false, updatable = false)
    private UUID tradePositionId;

    @Column(name = "trade_id", nullable = false)
    private UUID tradeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_strategy_id", nullable = false)
    private UUID userStrategyId;

    @Column(name = "asset", nullable = false, length = 30)
    private String asset;

    @Column(name = "interval", nullable = false, length = 20)
    private String interval;

    @Column(name = "exchange", nullable = false, length = 20)
    private String exchange;

    @Column(name = "side", nullable = false, length = 10)
    private String side; // LONG / SHORT

    @Column(name = "position_role", nullable = false, length = 20)
    private String positionRole; // TP1 / TP2 / RUNNER / SINGLE

    @Column(name = "status", nullable = false, length = 30)
    private String status; // OPEN / CLOSED

    @Column(name = "entry_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryPrice;

    @Column(name = "entry_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryQty;

    @Column(name = "entry_quote_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryQuoteQty;

    @Column(name = "remaining_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal remainingQty;

    @Column(name = "exit_price", precision = 24, scale = 12)
    private BigDecimal exitPrice;

    @Column(name = "exit_executed_qty", precision = 24, scale = 12)
    private BigDecimal exitExecutedQty;

    @Column(name = "exit_executed_quote_qty", precision = 24, scale = 12)
    private BigDecimal exitExecutedQuoteQty;

    @Column(name = "entry_fee", precision = 24, scale = 12)
    private BigDecimal entryFee;

    @Column(name = "entry_fee_currency", length = 20)
    private String entryFeeCurrency;

    @Column(name = "exit_fee", precision = 24, scale = 12)
    private BigDecimal exitFee;

    @Column(name = "exit_fee_currency", length = 20)
    private String exitFeeCurrency;

    @Column(name = "initial_stop_loss_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal initialStopLossPrice;

    @Column(name = "current_stop_loss_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal currentStopLossPrice;

    @Column(name = "trailing_stop_price", precision = 24, scale = 12)
    private BigDecimal trailingStopPrice;

    @Column(name = "take_profit_price", precision = 24, scale = 12)
    private BigDecimal takeProfitPrice;

    @Column(name = "highest_price_since_entry", precision = 24, scale = 12)
    private BigDecimal highestPriceSinceEntry;

    @Column(name = "lowest_price_since_entry", precision = 24, scale = 12)
    private BigDecimal lowestPriceSinceEntry;

    @Column(name = "realized_pnl_amount", precision = 24, scale = 12)
    private BigDecimal realizedPnlAmount;

    @Column(name = "realized_pnl_percent", precision = 12, scale = 6)
    private BigDecimal realizedPnlPercent;

    @Column(name = "exit_reason", length = 50)
    private String exitReason;

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}