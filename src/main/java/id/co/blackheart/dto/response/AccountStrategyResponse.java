package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class AccountStrategyResponse {

    private UUID accountStrategyId;
    private UUID accountId;
    private UUID strategyDefinitionId;
    private String strategyCode;
    private String symbol;
    private String intervalName;
    private Boolean enabled;
    private Boolean allowLong;
    private Boolean allowShort;
    private Integer maxOpenPositions;
    private BigDecimal capitalAllocationPct;
    private Integer priorityOrder;
    private String currentStatus;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
