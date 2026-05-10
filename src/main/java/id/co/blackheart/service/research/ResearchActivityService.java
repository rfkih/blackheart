package id.co.blackheart.service.research;

import id.co.blackheart.dto.response.ResearchActivityResponse;
import id.co.blackheart.dto.response.ResearchSessionSummaryResponse;
import id.co.blackheart.model.ResearchAgentActivity;
import id.co.blackheart.repository.ResearchAgentActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
            try {
                codes = (String[]) sqlArr.getArray();
            } catch (java.sql.SQLException ignored) {
                // Defensive fallback: keep the empty array when the JDBC
                // driver fails to materialise strategy_codes. The session
                // summary is informational, so a single column failure
                // shouldn't fail the whole listing endpoint.
            }
        }
        return new ResearchSessionSummaryResponse(
                (String) row[0],
                (String) row[1],
                toUtcOffset(row[2]),
                toUtcOffset(row[3]),
                row[4] != null ? ((Number) row[4]).intValue() : 0,
                Arrays.asList(codes),
                row[6] != null ? ((Number) row[6]).intValue() : 0,
                row[7] != null ? ((Number) row[7]).intValue() : 0,
                row[8] != null ? ((Number) row[8]).intValue() : 0,
                row[9] != null ? ((Number) row[9]).intValue() : 0,
                row[10] != null && (Boolean) row[10]
        );
    }

    /**
     * Native-query timestamp coercion. Hibernate 6 + the modern Postgres JDBC
     * driver hand back {@link Instant} for {@code timestamptz} columns rather
     * than {@link java.sql.Timestamp}, but tests / older driver builds may
     * still return Timestamp or LocalDateTime. Dispatch on the runtime type
     * so the listing endpoint doesn't ClassCastException whichever wins.
     */
    private static OffsetDateTime toUtcOffset(Object v) {
        if (v == null) return null;
        if (v instanceof OffsetDateTime odt) return odt.withOffsetSameInstant(ZoneOffset.UTC);
        if (v instanceof Instant inst) return inst.atOffset(ZoneOffset.UTC);
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        if (v instanceof LocalDateTime ldt) return ldt.atOffset(ZoneOffset.UTC);
        throw new IllegalStateException("Unsupported timestamp type from native query: " + v.getClass());
    }
}
