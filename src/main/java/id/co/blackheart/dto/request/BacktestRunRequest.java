package id.co.blackheart.dto.request;

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
public class BacktestRunRequest {

    private UUID userId;
    private String runName;
    private String symbol;
    private String interval;
    private String strategyName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal initialCapital;
    private BigDecimal feeRate;
    private BigDecimal slippageRate;
    private Boolean allowLong;
    private Boolean allowShort;
    private Integer maxOpenPositions;
}