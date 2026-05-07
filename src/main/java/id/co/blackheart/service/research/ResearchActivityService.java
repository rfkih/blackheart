package id.co.blackheart.service.research;

import id.co.blackheart.dto.response.ResearchActivityResponse;
import id.co.blackheart.dto.response.ResearchSessionSummaryResponse;
import id.co.blackheart.model.ResearchAgentActivity;
import id.co.blackheart.repository.ResearchAgentActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ResearchActivityService {

    private final ResearchAgentActivityRepository repository;

    public Page<ResearchActivityResponse> listActivities(UUID sessionId, Pageable pageable) {
        Page<ResearchAgentActivity> page = sessionId != null
                ? repository.findBySessionIdOrderByCreatedTimeAsc(sessionId, pageable)
                : repository.findAllByOrderByCreatedTimeDesc(pageable);
        return page.map(this::toResponse);
    }

    public List<ResearchSessionSummaryResponse> listSessions(int page, int size) {
        int offset = page * size;
        List<Object[]> rows = repository.findSessionSummaries(size, offset);
        return rows.stream().map(this::toSessionSummary).toList();
    }

    public long countSessions() {
        return repository.countDistinctSessions();
    }

    private ResearchActivityResponse toResponse(ResearchAgentActivity a) {
        return ResearchActivityResponse.from(
                a.getActivityId(), a.getSessionId(), a.getAgentName(),
                a.getActivityType(), a.getStrategyCode(), a.getTitle(),
                a.getDetails(), a.getRelatedId(), a.getRelatedType(),
                a.getStatus(), a.getCreatedTime()
        );
    }

    private ResearchSessionSummaryResponse toSessionSummary(Object[] row) {
        // row order: session_id, agent_name, started_at, last_activity_at,
        //            activity_count, strategy_codes, iterations_completed,
        //            significant_edge_count, no_edge_count, discard_count, goal_hit
        String[] codes = new String[0];
        if (row[5] instanceof java.sql.Array sqlArr) {
            try { codes = (String[]) sqlArr.getArray(); } catch (java.sql.SQLException ignored) {}
        }
        return new ResearchSessionSummaryResponse(
                (String) row[0],
                (String) row[1],
                row[2] != null ? ((java.sql.Timestamp) row[2]).toInstant().atOffset(ZoneOffset.UTC) : null,
                row[3] != null ? ((java.sql.Timestamp) row[3]).toInstant().atOffset(ZoneOffset.UTC) : null,
                row[4] != null ? ((Number) row[4]).intValue() : 0,
                Arrays.asList(codes),
                row[6] != null ? ((Number) row[6]).intValue() : 0,
                row[7] != null ? ((Number) row[7]).intValue() : 0,
                row[8] != null ? ((Number) row[8]).intValue() : 0,
                row[9] != null ? ((Number) row[9]).intValue() : 0,
                row[10] != null && (Boolean) row[10]
        );
    }
}
