package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "backtest_equity_point")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BacktestEquityPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "backtest_equity_point_id", nullable = false, updatable = false)
    private UUID backtestEquityPointId;

    @Column(name = "backtest_run_id", nullable = false)
    private UUID backtestRunId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "point_time", nullable = false)
    private LocalDateTime pointTime;

    @Column(name = "cash_balance", nullable = false, precision = 24, scale = 12)
    private BigDecimal cashBalance;

    @Column(name = "asset_value", nullable = false, precision = 24, scale = 12)
    private BigDecimal assetValue;

    @Column(name = "total_equity", nullable = false, precision = 24, scale = 12)
    private BigDecimal totalEquity;

    @Column(name = "drawdown_percent", precision = 12, scale = 6)
    private BigDecimal drawdownPercent;

    @Column(name = "open_positions", nullable = false)
    private Integer openPositions;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}