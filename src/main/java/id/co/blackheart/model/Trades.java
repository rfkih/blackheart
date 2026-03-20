package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trades")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Trades {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trade_id", nullable = false, updatable = false)
    private UUID tradeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_strategy_id")
    private UUID userStrategyId;

    @Column(name = "strategy_name", nullable = false, length = 50)
    private String strategyName;

    @Column(name = "interval", nullable = false, length = 10)
    private String interval;

    @Column(name = "exchange", nullable = false, length = 10)
    private String exchange;

    @Column(name = "asset", nullable = false, length = 20)
    private String asset;

    @Column(name = "side", nullable = false, length = 10)
    private String side;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "entry_order_id", nullable = false)
    private Long entryOrderId;

    @Column(name = "exit_order_id")
    private Long exitOrderId;

    @Column(name = "entry_executed_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryExecutedQty;

    @Column(name = "entry_executed_quote_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryExecutedQuoteQty;

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

    @Column(name = "entry_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 24, scale = 12)
    private BigDecimal exitPrice;

    @Column(name = "pl_percent", precision = 12, scale = 6)
    private BigDecimal plPercent;

    @Column(name = "pl_amount", precision = 24, scale = 12)
    private BigDecimal plAmount;

    @Column(name = "initial_stop_loss_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal initialStopLossPrice;

    @Column(name = "current_stop_loss_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal currentStopLossPrice;

    @Column(name = "trailing_stop_price", precision = 24, scale = 12)
    private BigDecimal trailingStopPrice;

    @Column(name = "take_profit_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal takeProfitPrice;

    @Column(name = "exit_reason", length = 30)
    private String exitReason;

    @Column(name = "entry_trend_regime", length = 10)
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
}