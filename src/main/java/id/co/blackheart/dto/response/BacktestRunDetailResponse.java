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
    private BacktestMetricsResponse metrics;
}
