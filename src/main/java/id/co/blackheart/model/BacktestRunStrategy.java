package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "backtest_run_strategy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestRunStrategy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "backtest_run_strategy_id", nullable = false, updatable = false)
    private UUID backtestRunStrategyId;

    @Column(name = "backtest_run_id", nullable = false)
    private UUID backtestRunId;

    @Column(name = "strategy_name", nullable = false, length = 100)
    private String strategyName;

    @Column(name = "strategy_version", length = 50)
    private String strategyVersion;

    @Column(name = "parameter_json", columnDefinition = "TEXT")
    private String parameterJson;

    @Column(name = "weight", precision = 12, scale = 6)
    private java.math.BigDecimal weight;

}