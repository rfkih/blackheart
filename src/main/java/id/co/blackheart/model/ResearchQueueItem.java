package id.co.blackheart.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row in the unattended-research work queue. The orchestrator
 * ({@code research-tick.sh}) atomically claims the highest-priority
 * PENDING row each tick, runs one sweep iteration, and updates the row.
 *
 * <p>Schema mirrors {@code db/flyway/V13__create_research_queue.sql}.
 * Sweep grid is JSONB ({@code {"params":[{"name":..., "values":[...]}]}}); the
 * orchestrator computes the cartesian product and indexes into it via
 * {@code iteration_number}.
 */
@Entity
@Table(name = "research_queue")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchQueueItem {

    @Id
    @Column(name = "queue_id", nullable = false, updatable = false)
    private UUID queueId;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "strategy_code", nullable = false, length = 60)
    private String strategyCode;

    @Column(name = "interval_name", nullable = false, length = 20)
    private String intervalName;

    @Column(name = "instrument", nullable = false, length = 30)
    private String instrument;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sweep_config", nullable = false, columnDefinition = "jsonb")
    private JsonNode sweepConfig;

    @Column(name = "hypothesis")
    private String hypothesis;

    /** PENDING / RUNNING / PARKED / COMPLETED / FAILED — see V13 CHECK constraint. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "iteration_number", nullable = false)
    private Integer iterationNumber;

    @Column(name = "iter_budget", nullable = false)
    private Integer iterBudget;

    @Column(name = "early_stop_on_no_edge", nullable = false)
    private Boolean earlyStopOnNoEdge;

    @Column(name = "require_walk_forward", nullable = false)
    private Boolean requireWalkForward;

    @Column(name = "last_iteration_id")
    private UUID lastIterationId;

    @Column(name = "last_run_id")
    private UUID lastRunId;

    @Column(name = "final_verdict", length = 40)
    private String finalVerdict;

    @Column(name = "walk_forward_id")
    private UUID walkForwardId;

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "notes")
    private String notes;
}
