package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "monte_carlo_run")
public class MonteCarloRun {

    @Id
    @Column(name = "monte_carlo_run_id", nullable = false, updatable = false)
    private UUID monteCarloRunId;

    @Column(name = "backtest_run_id", nullable = false)
    private UUID backtestRunId;

    @Column(name = "account_strategy_id")
    private UUID accountStrategyId;

    @Column(name = "simulation_mode", length = 50, nullable = false)
    private String simulationMode;

    @Column(name = "number_of_simulations", nullable = false)
    private Integer numberOfSimulations;

    @Column(name = "trades_used", nullable = false)
    private Integer tradesUsed;

    @Column(name = "horizon_trades")
    private Integer horizonTrades;

    @Column(name = "initial_capital", precision = 24, scale = 8, nullable = false)
    private BigDecimal initialCapital;

    @Column(name = "ruin_threshold_pct", precision = 12, scale = 6)
    private BigDecimal ruinThresholdPct;

    @Column(name = "max_acceptable_drawdown_pct", precision = 12, scale = 6)
    private BigDecimal maxAcceptableDrawdownPct;

    @Column(name = "random_seed")
    private Long randomSeed;

    @Column(name = "effective_seed")
    private Long effectiveSeed;

    // ── Distribution metrics ───────────────────────────────────────────────────
    @Column(name = "mean_final_equity", precision = 24, scale = 8)
    private BigDecimal meanFinalEquity;

    @Column(name = "median_final_equity", precision = 24, scale = 8)
    private BigDecimal medianFinalEquity;

    @Column(name = "min_final_equity", precision = 24, scale = 8)
    private BigDecimal minFinalEquity;

    @Column(name = "max_final_equity", precision = 24, scale = 8)
    private BigDecimal maxFinalEquity;

    @Column(name = "p5_final_equity", precision = 24, scale = 8)
    private BigDecimal p5FinalEquity;

    @Column(name = "p25_final_equity", precision = 24, scale = 8)
    private BigDecimal p25FinalEquity;

    @Column(name = "p75_final_equity", precision = 24, scale = 8)
    private BigDecimal p75FinalEquity;

    @Column(name = "p95_final_equity", precision = 24, scale = 8)
    private BigDecimal p95FinalEquity;

    @Column(name = "mean_total_return_pct", precision = 12, scale = 6)
    private BigDecimal meanTotalReturnPct;

    @Column(name = "median_total_return_pct", precision = 12, scale = 6)
    private BigDecimal medianTotalReturnPct;

    @Column(name = "mean_max_drawdown_pct", precision = 12, scale = 6)
    private BigDecimal meanMaxDrawdownPct;

    @Column(name = "median_max_drawdown_pct", precision = 12, scale = 6)
    private BigDecimal medianMaxDrawdownPct;

    @Column(name = "worst_max_drawdown_pct", precision = 12, scale = 6)
    private BigDecimal worstMaxDrawdownPct;

    // ── Risk metrics ────────────────────────────────────────────────────────────
    @Column(name = "probability_of_ruin", precision = 12, scale = 6)
    private BigDecimal probabilityOfRuin;

    @Column(name = "probability_of_drawdown_breach", precision = 12, scale = 6)
    private BigDecimal probabilityOfDrawdownBreach;

    @Column(name = "probability_of_profit", precision = 12, scale = 6)
    private BigDecimal probabilityOfProfit;

    @Column(name = "status", length = 20)
    private String status;

    @CreationTimestamp
    @Column(name = "created_time", updatable = false)
    private LocalDateTime createdTime;
}