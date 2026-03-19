package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "backtest_trade")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "backtest_trade_id", nullable = false, updatable = false)
    private UUID backtestTradeId;

    @Column(name = "backtest_run_id", nullable = false)
    private UUID backtestRunId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "strategy_name", nullable = false, length = 100)
    private String strategyName;

    @Column(name = "interval", nullable = false, length = 10)
    private String interval;

    @Column(name = "asset", nullable = false, length = 20)
    private String asset;

    @Column(name = "side", nullable = false, length = 10)
    private String side;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @Column(name = "entry_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 24, scale = 12)
    private BigDecimal exitPrice;

    @Column(name = "entry_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryQty;

    @Column(name = "exit_qty", precision = 24, scale = 12)
    private BigDecimal exitQty;

    @Column(name = "entry_quote_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal entryQuoteQty;

    @Column(name = "exit_quote_qty", precision = 24, scale = 12)
    private BigDecimal exitQuoteQty;

    @Column(name = "entry_fee", precision = 24, scale = 12)
    private BigDecimal entryFee;

    @Column(name = "exit_fee", precision = 24, scale = 12)
    private BigDecimal exitFee;

    @Column(name = "slippage_amount", precision = 24, scale = 12)
    private BigDecimal slippageAmount;

    @Column(name = "initial_stop_loss_price", precision = 24, scale = 12)
    private BigDecimal initialStopLossPrice;

    @Column(name = "current_stop_loss_price", precision = 24, scale = 12)
    private BigDecimal currentStopLossPrice;

    @Column(name = "trailing_stop_price", precision = 24, scale = 12)
    private BigDecimal trailingStopPrice;

    @Column(name = "take_profit_price", precision = 24, scale = 12)
    private BigDecimal takeProfitPrice;

    @Column(name = "exit_reason", length = 30)
    private String exitReason;

    @Column(name = "entry_trend_regime", length = 20)
    private String entryTrendRegime;

    @Column(name = "entry_adx", precision = 24, scale = 8)
    private BigDecimal entryAdx;

    @Column(name = "entry_atr", precision = 24, scale = 8)
    private BigDecimal entryAtr;

    @Column(name = "entry_rsi", precision = 24, scale = 8)
    private BigDecimal entryRsi;

    @Column(name = "pl_percent", precision = 12, scale = 6)
    private BigDecimal plPercent;

    @Column(name = "pl_amount", precision = 24, scale = 12)
    private BigDecimal plAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}