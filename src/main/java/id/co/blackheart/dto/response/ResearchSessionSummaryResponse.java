package id.co.blackheart.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record ResearchSessionSummaryResponse(
        String sessionId,
        String agentName,
        OffsetDateTime startedAt,
        OffsetDateTime lastActivityAt,
        int activityCount,
        List<String> strategyCodes,
        int iterationsCompleted,
        int significantEdgeCount,
        int noEdgeCount,
        int discardCount,
        boolean goalHit
) {}
