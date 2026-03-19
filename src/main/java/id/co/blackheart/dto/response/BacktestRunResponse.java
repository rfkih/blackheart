package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRunResponse {

    private UUID backtestRunId;

    private UUID userId;

    private String runName;

    private String strategyName;

    private String symbol;

    private String interval;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal initialCapital;

    private BigDecimal finalCapital;

    private BigDecimal feeRate;

    private BigDecimal slippageRate;

    private Boolean allowLong;

    private Boolean allowShort;

    private Integer maxOpenPositions;

    private Integer totalTrades;

    private Integer winningTrades;

    private Integer losingTrades;

    private BigDecimal winRate;

    private BigDecimal profitFactor;

    private BigDecimal maxDrawdownPercent;

    private BigDecimal totalReturnPercent;

    private BigDecimal sharpeRatio;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}