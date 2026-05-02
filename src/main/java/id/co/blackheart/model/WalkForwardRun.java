package id.co.blackheart.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read-only mirror of {@code walk_forward_run} (V12). The orchestrator
 * (Python) writes these rows after every {@code POST /walk-forward};
 * the trading JVM reads them via {@link id.co.blackheart.repository.WalkForwardRunRepository}
 * to surface stability verdicts on the operator dashboard.
 */
@Entity
@Table(name = "walk_forward_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalkForwardRun {

    @Id
    @Column(name = "walk_forward_id", nullable = false, updatable = false)
    private UUID walkForwardId;

    @Column(name = "strategy_code", nullable = false, length = 60)
    private String strategyCode;

    @Column(name = "interval_name", nullable = false, length = 20)
    private String intervalName;

    @Column(name = "instrument", nullable = false, length = 30)
    private String instrument;

    @Column(name = "full_window_start", nullable = false)
    private LocalDateTime fullWindowStart;

    @Column(name = "full_window_end", nullable = false)
    private LocalDateTime fullWindowEnd;

    @Column(name = "train_months", nullable = false)
    private Integer trainMonths;

    @Column(name = "test_months", nullable = false)
    private Integer testMonths;

    @Column(name = "n_folds", nullable = false)
    private Integer nFolds;

    @Column(name = "fold_pf_mean", precision = 12, scale = 6)
    private BigDecimal foldPfMean;

    @Column(name = "fold_pf_std", precision = 12, scale = 6)
    private BigDecimal foldPfStd;

    @Column(name = "fold_pf_min", precision = 12, scale = 6)
    private BigDecimal foldPfMin;

    @Column(name = "fold_pf_max", precision = 12, scale = 6)
    private BigDecimal foldPfMax;

    @Column(name = "fold_pf_positive_pct", precision = 5, scale = 2)
    private BigDecimal foldPfPositivePct;

    @Column(name = "fold_return_mean", precision = 12, scale = 6)
    private BigDecimal foldReturnMean;

    @Column(name = "fold_return_std", precision = 12, scale = 6)
    private BigDecimal foldReturnStd;

    @Column(name = "fold_sharpe_mean", precision = 12, scale = 6)
    private BigDecimal foldSharpeMean;

    @Column(name = "fold_sharpe_std", precision = 12, scale = 6)
    private BigDecimal foldSharpeStd;

    @Column(name = "total_trades_across_folds")
    private Integer totalTradesAcrossFolds;

    @Column(name = "stability_verdict", nullable = false, length = 40)
    private String stabilityVerdict;

    @Column(name = "motivating_iteration_id")
    private UUID motivatingIterationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fold_results", nullable = false, columnDefinition = "jsonb")
    private JsonNode foldResults;

    @Column(name = "git_commit_hash", length = 64)
    private String gitCommitHash;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;

    @Column(name = "created_by", length = 150)
    private String createdBy;
}
