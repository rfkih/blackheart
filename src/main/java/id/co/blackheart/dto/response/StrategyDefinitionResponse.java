package id.co.blackheart.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrategyDefinitionResponse {

    private UUID strategyDefinitionId;
    private String strategyCode;
    private String strategyName;
    private String strategyType;
    private String description;
    private String status;
    private String archetype;
    private Integer archetypeVersion;
    private Map<String, Object> specJsonb;
    private Integer specSchemaVersion;
    private Boolean isDeleted;
    private LocalDateTime deletedAt;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
