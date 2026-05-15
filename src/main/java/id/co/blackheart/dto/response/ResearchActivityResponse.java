package id.co.blackheart.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

public record ResearchActivityResponse(
        UUID activityId,
        UUID sessionId,
        String agentName,
        String activityType,
        String strategyCode,
        String title,
        Map<String, Object> details,
        UUID relatedId,
        String relatedType,
        String status,
        OffsetDateTime createdAt
) {
    public static ResearchActivityResponse from(
            UUID activityId, UUID sessionId, String agentName, String activityType,
            String strategyCode, String title, Map<String, Object> details,
            UUID relatedId, String relatedType, String status, LocalDateTime createdTime) {
        return new ResearchActivityResponse(
                activityId, sessionId, agentName, activityType, strategyCode, title,
                details, relatedId, relatedType, status,
                createdTime != null ? createdTime.atOffset(ZoneOffset.UTC) : null);
    }
}
