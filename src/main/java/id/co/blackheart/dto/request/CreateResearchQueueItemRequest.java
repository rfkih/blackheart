package id.co.blackheart.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin-create payload for a research queue row. Mirrors the CLI
 * {@code research/scripts/queue-strategy.sh} so anything queueable from
 * the shell is queueable from the UI.
 */
@Data
public class CreateResearchQueueItemRequest {

    @NotBlank
    @Size(max = 60)
    private String strategyCode;

    @NotBlank
    @Size(max = 20)
    private String intervalName;

    @Size(max = 30)
    private String instrument;

    /** Sweep grid: {@code {"params":[{"name":"X","values":[1,2,3]}]}}. */
    @NotNull
    private JsonNode sweepConfig;

    private String hypothesis;

    @NotNull
    @Min(1)
    @Max(64)
    private Integer iterBudget;

    /** Lower number = sooner. Orchestrator sorts ascending. Defaults to 100. */
    @Min(1)
    @Max(1000)
    private Integer priority;

    private Boolean earlyStopOnNoEdge;
    private Boolean requireWalkForward;
}
