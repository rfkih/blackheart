package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-row global pause flag for the autonomous research loop. Read by
 * {@code research-tick.sh} on every tick — when {@code paused=true}, the tick
 * exits clean without claiming any queue row. Mutated through
 * {@code POST /api/v1/research/control/{pause,resume}} (admin-only).
 *
 * The table has a CHECK constraint forcing {@code control_id = 1}, so there
 * will only ever be one row. Treat the constructor and any {@code save()} as
 * an UPDATE, never an INSERT (V23 seeds the row).
 */
@Entity
@Table(name = "research_control")
@Getter
@Setter
public class ResearchControl {

    @Id
    @Column(name = "control_id", nullable = false)
    private Integer controlId;

    @Column(name = "paused", nullable = false)
    private boolean paused;

    @Column(name = "reason")
    private String reason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;
}
