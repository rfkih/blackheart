package id.co.blackheart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Partial update — only priority is mutable post-creation. Status changes
 * go through dedicated endpoints (cancel) to keep the orchestrator's
 * state machine clean.
 */
@Data
public class UpdateResearchQueueItemRequest {

    @Min(1)
    @Max(1000)
    private Integer priority;
}
