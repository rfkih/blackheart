package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class BacktestRunDetailResponse {
    private UUID id;
    private UUID accountStrategyId;
    private Map<String, UUID> strategyAccountStrategyIds;
    private String strategyCode;
    private String strategyName;
    private String symbol;
    private String interval;
    private String status;
    /** 0–100 while the run is PENDING/RUNNING. 100 on COMPLETED. Kept at the
     *  last reported value on FAILED. */
    private Integer progressPercent;
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private BigDecimal initialCapital;
    private BigDecimal endingBalance;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private Object paramSnapshot;
    /** Reproducibility manifest — git SHA + app version captured at submit. */
    private String gitCommitSha;
    private String appVersion;
    /** Origin tag — USER (wizard) or RESEARCHER (autonomous orchestrator). */
    private String triggeredBy;
    /** Run-level config the wizard needs to faithfully reproduce a run via
     *  "Re-run with these params". {@code paramSnapshot} only carries
     *  per-strategy override maps; these fields carry the rest. */
    private Boolean allowLong;
    private Boolean allowShort;
    private Integer maxConcurrentStrategies;
    private Map<String, BigDecimal> strategyAllocations;
    private Map<String, String> strategyIntervals;
    /** Flat funding-rate stub used at submit time (basis points per 8h),
     *  applied per-position at close. Null on legacy runs that pre-date V22. */
    private BigDecimal fundingRateBpsPer8h;
    private BacktestMetricsResponse metrics;
}
