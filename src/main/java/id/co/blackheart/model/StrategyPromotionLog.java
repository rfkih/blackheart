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
 * Append-only audit row for every promote/demote transition. The
 * {@code chk_promotion_states} CHECK constraint in V15 enforces legal
 * {@code (from_state, to_state)} pairs at the DB layer; V40 added the
 * {@code chk_promotion_log_scope} CHECK so each row is unambiguously
 * either account-scoped (V15+) or definition-scoped (V40+).
 *
 * <p>Schema: see {@code db/flyway/V15__create_promotion_pipeline.sql}
 * and {@code db/flyway/V40__add_promotion_state_to_strategy_definition.sql}.
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

    /**
     * Set for account-scoped (V15) promotions. Null for definition-scoped (V40).
     * The {@code chk_promotion_log_scope} CHECK constraint enforces that exactly
     * one of {@code accountStrategyId} / {@code strategyDefinitionId} is set.
     */
    @Column(name = "account_strategy_id")
    private UUID accountStrategyId;

    /**
     * Set for definition-scoped (V40) promotions. Null for account-scoped rows.
     */
    @Column(name = "strategy_definition_id")
    private UUID strategyDefinitionId;

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
