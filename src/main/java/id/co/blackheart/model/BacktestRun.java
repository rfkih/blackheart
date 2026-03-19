package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "backtest_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "backtest_run_id", nullable = false, updatable = false)
    private UUID backtestRunId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "run_name", length = 100)
    private String runName;

    @Column(name = "strategy_name", nullable = false, length = 100)
    private String strategyName;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "interval", nullable = false, length = 10)
    private String interval;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "initial_capital", nullable = false, precision = 24, scale = 12)
    private BigDecimal initialCapital;

    @Column(name = "final_capital", precision = 24, scale = 12)
    private BigDecimal finalCapital;

    @Column(name = "fee_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal feeRate;

    @Column(name = "slippage_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal slippageRate;

    @Column(name = "allow_long", nullable = false)
    private Boolean allowLong;

    @Column(name = "allow_short", nullable = false)
    private Boolean allowShort;

    @Column(name = "max_open_positions", nullable = false)
    private Integer maxOpenPositions;

    @Column(name = "total_trades")
    private Integer totalTrades;

    @Column(name = "winning_trades")
    private Integer winningTrades;

    @Column(name = "losing_trades")
    private Integer losingTrades;

    @Column(name = "win_rate", precision = 12, scale = 6)
    private BigDecimal winRate;

    @Column(name = "profit_factor", precision = 24, scale = 12)
    private BigDecimal profitFactor;

    @Column(name = "max_drawdown_percent", precision = 12, scale = 6)
    private BigDecimal maxDrawdownPercent;

    @Column(name = "total_return_percent", precision = 12, scale = 6)
    private BigDecimal totalReturnPercent;

    @Column(name = "sharpe_ratio", precision = 12, scale = 6)
    private BigDecimal sharpeRatio;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}