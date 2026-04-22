package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class StrategyDefinitionResponse {

    private UUID strategyDefinitionId;
    private String strategyCode;
    private String strategyName;
    private String strategyType;
    private String description;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
