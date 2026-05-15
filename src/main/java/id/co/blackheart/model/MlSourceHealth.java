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

/**
 * Per-source health snapshot for the "ML Data Sources" admin dashboard. One
 * row per source (PK on {@code source}); updated by every live-ingest tick.
 *
 * <p><b>health_status</b> rollup:
 * <ul>
 *   <li>{@code healthy} — no consecutive failures, no recent PIT rejections.</li>
 *   <li>{@code degraded} — some failures or PIT rejections but still pulling.</li>
 *   <li>{@code failed} — {@code consecutiveFailures >= 3}.</li>
 *   <li>{@code disabled} — all schedules for this source have {@code enabled=false}.</li>
 *   <li>{@code unknown} — never ticked.</li>
 * </ul>
 *
 * <p>Monitor cron alerts (Telegram) on transition to {@code degraded} or
 * {@code failed}. Frontend dashboard polls every 30s.
 *
 * <p><b>rejectedPitViolationsTotal</b> (V68, was {@code _24h}): cumulative
 * rows rejected by PIT guards (event_time > ingestion_time + skew, or
 * backfill-lag too large). Non-zero is a signal the source's timestamps
 * are drifting — investigate before relying on the data. Counter is
 * lifetime, not rolling — see V68 commentary.
 */
@Entity
@Table(name = "ml_source_health")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlSourceHealth extends BaseEntity {

    @Id
    @Column(name = "source", length = 80)
    private String source;

    @Column(name = "last_pull_at")
    private LocalDateTime lastPullAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures;

    @Column(name = "rows_inserted_total", nullable = false)
    private Long rowsInsertedTotal;

    @Column(name = "errors_total", nullable = false)
    private Integer errorsTotal;

    @Column(name = "rejected_pit_violations_total", nullable = false)
    private Integer rejectedPitViolationsTotal;

    @Column(name = "health_status", nullable = false, length = 20)
    private String healthStatus;

    @Column(name = "health_message", columnDefinition = "TEXT")
    private String healthMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics")
    private JsonNode metrics;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
