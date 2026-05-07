package id.co.blackheart.repository;

import id.co.blackheart.model.ResearchAgentActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchAgentActivityRepository extends JpaRepository<ResearchAgentActivity, UUID> {

    Page<ResearchAgentActivity> findBySessionIdOrderByCreatedTimeAsc(UUID sessionId, Pageable pageable);

    Page<ResearchAgentActivity> findByAgentNameOrderByCreatedTimeDesc(String agentName, Pageable pageable);

    Page<ResearchAgentActivity> findAllByOrderByCreatedTimeDesc(Pageable pageable);

    @Query(nativeQuery = true, value = """
            SELECT
                session_id::text,
                agent_name,
                MIN(created_time)   AS started_at,
                MAX(created_time)   AS last_activity_at,
                COUNT(*)::int     AS activity_count,
                array_agg(DISTINCT strategy_code) FILTER (WHERE strategy_code IS NOT NULL) AS strategy_codes,
                COUNT(*) FILTER (WHERE activity_type = 'ITERATION_COMPLETED')::int AS iterations_completed,
                COUNT(*) FILTER (WHERE activity_type = 'ITERATION_COMPLETED'
                    AND details->>'statistical_verdict' = 'SIGNIFICANT_EDGE')::int AS significant_edge_count,
                COUNT(*) FILTER (WHERE activity_type = 'ITERATION_COMPLETED'
                    AND details->>'statistical_verdict' = 'NO_EDGE')::int AS no_edge_count,
                COUNT(*) FILTER (WHERE activity_type = 'ITERATION_COMPLETED'
                    AND details->>'statistical_verdict' = 'DISCARD')::int AS discard_count,
                BOOL_OR(activity_type = 'GOAL_HIT') AS goal_hit
            FROM research_agent_activity
            GROUP BY session_id, agent_name
            ORDER BY MAX(created_time) DESC
            LIMIT :limit OFFSET :offset
            """)
    List<Object[]> findSessionSummaries(@Param("limit") int limit, @Param("offset") int offset);

    @Query(nativeQuery = true, value = "SELECT COUNT(DISTINCT session_id) FROM research_agent_activity")
    long countDistinctSessions();
}
