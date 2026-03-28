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
@Table(name = "backtest_run")
public class BacktestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "backtest_run_id", nullable = false, updatable = false)
    private UUID backtestRunId;

    @Column(name = "account_strategy_id")
    private UUID accountStrategyId;

    @Column(name = "strategy_name", length = 100, nullable = false)
    private String strategyName;

    @Column(name = "asset", length = 30, nullable = false)
    private String asset;

    @Column(name = "interval", length = 20, nullable = false)
    private String interval;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "initial_capital", precision = 24, scale = 8, nullable = false)
    private BigDecimal initialCapital;

    @Column(name = "risk_per_trade_pct", precision = 12, scale = 6)
    private BigDecimal riskPerTradePct;

    @Column(name = "fee_pct", precision = 12, scale = 6)
    private BigDecimal feePct;

    @Column(name = "slippage_pct", precision = 12, scale = 6)
    private BigDecimal slippagePct;

    @Column(name = "min_notional", precision = 24, scale = 8)
    private BigDecimal minNotional;

    @Column(name = "min_qty", precision = 24, scale = 8)
    private BigDecimal minQty;

    @Column(name = "qty_step", precision = 24, scale = 8)
    private BigDecimal qtyStep;

    @Column(name = "total_trades")
    private Integer totalTrades;

    @Column(name = "total_wins")
    private Integer totalWins;

    @Column(name = "total_losses")
    private Integer totalLosses;

    @Column(name = "win_rate", precision = 12, scale = 6)
    private BigDecimal winRate;

    @Column(name = "gross_profit", precision = 24, scale = 8)
    private BigDecimal grossProfit;

    @Column(name = "gross_loss", precision = 24, scale = 8)
    private BigDecimal grossLoss;

    @Column(name = "net_profit", precision = 24, scale = 8)
    private BigDecimal netProfit;

    @Column(name = "max_drawdown_pct", precision = 12, scale = 6)
    private BigDecimal maxDrawdownPct;

    @Column(name = "ending_balance", precision = 24, scale = 8)
    private BigDecimal endingBalance;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @UpdateTimestamp
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}