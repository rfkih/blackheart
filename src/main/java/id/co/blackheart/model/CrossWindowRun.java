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
 * V38 cross_window_run row — a regime-stratified cross-window validation
 * result. Written by the research orchestrator's cross_window service;
 * the trading JVM only reads it (UI-driven verdict surfacing on the
 * promotion dialog and strategy detail panel).
 */
@Entity
@Table(name = "cross_window_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrossWindowRun {

    @Id
    @Column(name = "cross_window_id", nullable = false, updatable = false)
    private UUID crossWindowId;

    @Column(name = "strategy_code", nullable = false, length = 60)
    private String strategyCode;

    @Column(name = "interval_name", nullable = false, length = 20)
    private String intervalName;

    @Column(name = "instrument", nullable = false, length = 30)
    private String instrument;

    @Column(name = "windows_catalog_version", nullable = false, length = 40)
    private String windowsCatalogVersion;

    @Column(name = "n_windows", nullable = false)
    private Integer nWindows;

    @Column(name = "n_windows_completed", nullable = false)
    private Integer nWindowsCompleted;

    @Column(name = "window_pf_mean")
    private BigDecimal windowPfMean;

    @Column(name = "window_pf_std")
    private BigDecimal windowPfStd;

    @Column(name = "window_pf_min")
    private BigDecimal windowPfMin;

    @Column(name = "window_pf_max")
    private BigDecimal windowPfMax;

    @Column(name = "pct_windows_net_positive")
    private BigDecimal pctWindowsNetPositive;

    @Column(name = "window_return_mean")
    private BigDecimal windowReturnMean;

    @Column(name = "window_return_std")
    private BigDecimal windowReturnStd;

    @Column(name = "total_trades_across_windows")
    private Integer totalTradesAcrossWindows;

    @Column(name = "cross_window_verdict", nullable = false, length = 40)
    private String crossWindowVerdict;

    @Column(name = "motivating_iteration_id")
    private UUID motivatingIterationId;

    @Column(name = "motivating_walk_forward_id")
    private UUID motivatingWalkForwardId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "window_results")
    private JsonNode windowResults;

    @Column(name = "git_commit_hash", length = 64)
    private String gitCommitHash;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;

    @Column(name = "created_by", length = 150)
    private String createdBy;
}
