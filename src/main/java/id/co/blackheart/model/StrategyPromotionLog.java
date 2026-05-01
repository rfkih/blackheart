package id.co.blackheart.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only audit row for every promote/demote transition on an
 * account_strategy. The {@code chk_promotion_states} CHECK constraint
 * in V15 enforces legal {@code (from_state, to_state)} pairs at the DB
 * layer — bypassing requires ALTER TABLE which leaves its own audit trail.
 *
 * <p>Schema: see {@code db/flyway/V15__create_promotion_pipeline.sql}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "strategy_promotion_log")
public class StrategyPromotionLog {

    @Id
    @Column(name = "promotion_id", nullable = false, updatable = false)
    private UUID promotionId;

    @Column(name = "account_strategy_id", nullable = false)
    private UUID accountStrategyId;

    @Column(name = "strategy_code", nullable = false, length = 20)
    private String strategyCode;

    @Column(name = "from_state", nullable = false, length = 20)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 20)
    private String toState;

    @Column(name = "reviewer_user_id")
    private UUID reviewerUserId;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "evidence", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode evidence;

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;
}
