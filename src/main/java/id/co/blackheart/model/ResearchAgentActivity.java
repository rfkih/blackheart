package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "research_agent_activity")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResearchAgentActivity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "activity_type", nullable = false)
    private String activityType;

    @Column(name = "strategy_code")
    private String strategyCode;

    @Column(name = "title", nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "related_id")
    private UUID relatedId;

    @Column(name = "related_type")
    private String relatedType;

    @Column(name = "status", nullable = false)
    private String status;
}
