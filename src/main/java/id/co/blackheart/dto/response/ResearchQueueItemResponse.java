package id.co.blackheart.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.blackheart.model.ResearchQueueItem;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ResearchQueueItemResponse {

    private UUID queueId;
    private Integer priority;
    private String strategyCode;
    private String intervalName;
    private String instrument;
    private JsonNode sweepConfig;
    private String hypothesis;
    private String status;
    private Integer iterationNumber;
    private Integer iterBudget;
    private Boolean earlyStopOnNoEdge;
    private Boolean requireWalkForward;
    private UUID lastIterationId;
    private UUID lastRunId;
    private String finalVerdict;
    private UUID walkForwardId;
    private LocalDateTime createdTime;
    private String createdBy;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String notes;

    public static ResearchQueueItemResponse from(ResearchQueueItem q) {
        return ResearchQueueItemResponse.builder()
                .queueId(q.getQueueId())
                .priority(q.getPriority())
                .strategyCode(q.getStrategyCode())
                .intervalName(q.getIntervalName())
                .instrument(q.getInstrument())
                .sweepConfig(q.getSweepConfig())
                .hypothesis(q.getHypothesis())
                .status(q.getStatus())
                .iterationNumber(q.getIterationNumber())
                .iterBudget(q.getIterBudget())
                .earlyStopOnNoEdge(q.getEarlyStopOnNoEdge())
                .requireWalkForward(q.getRequireWalkForward())
                .lastIterationId(q.getLastIterationId())
                .lastRunId(q.getLastRunId())
                .finalVerdict(q.getFinalVerdict())
                .walkForwardId(q.getWalkForwardId())
                .createdTime(q.getCreatedTime())
                .createdBy(q.getCreatedBy())
                .startedAt(q.getStartedAt())
                .completedAt(q.getCompletedAt())
                .notes(q.getNotes())
                .build();
    }
}
