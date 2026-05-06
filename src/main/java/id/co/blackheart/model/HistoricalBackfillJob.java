package id.co.blackheart.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Job row for the historical data integrity console. The controller inserts
 * a PENDING row and returns immediately; the async runner flips it through
 * RUNNING and on into SUCCESS / FAILED / CANCELLED.
 *
 * <p>One {@link HistoricalBackfillJob} ≈ one (symbol, interval) repair
 * operation. The orchestrator submits multi-pair work as multiple rows so
 * each remains independently observable and cancellable.
 *
 * <p>JSONB columns ({@code params}, {@code result}) use
 * {@link JdbcTypeCode}{@code (SqlTypes.JSON)} — the project rule is to never
 * use {@code AttributeConverter} for JSONB.
 */
/**
 * <p><b>Equality</b>: keyed on {@code jobId}, not all fields. Lombok's
 * {@code @Data} would equal-and-hash over every column (including JSONB
 * params + result), which is both expensive and incorrect for JPA managed
 * entities — adding the same row to two collections during a session would
 * compare unflushed state.
 *
 * <p><b>Sensitive data warning</b>: {@code params} and {@code result} are
 * exposed verbatim by {@code GET /api/v1/historical/jobs/{id}}. Do not stash
 * credentials, API keys, or PII in handler params — assume operators with
 * the ADMIN role can read them. Stick to plain backfill scoping fields
 * (mode, from, to, column).
 */
@Entity
@Table(name = "historical_backfill_job")
@Getter
@Setter
@ToString(of = {"jobId", "jobType", "status", "symbol", "interval"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalBackfillJob {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 80)
    private JobType jobType;

    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "interval", length = 10)
    private String interval;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private JsonNode params;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobStatus status;

    @Column(name = "phase", length = 120)
    private String phase;

    @Column(name = "progress_done", nullable = false)
    private int progressDone;

    @Column(name = "progress_total", nullable = false)
    private int progressTotal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result")
    private JsonNode result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_class", length = 200)
    private String errorClass;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * Identity equality keyed on {@code jobId}. Two managed entities with the
     * same UUID are considered equal regardless of mutation state — matches
     * Hibernate's session-cache semantics and avoids full-field deep
     * comparisons over the JSONB columns.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HistoricalBackfillJob that)) return false;
        return jobId != null && jobId.equals(that.jobId);
    }

    @Override
    public int hashCode() {
        // Use a constant rather than jobId.hashCode() so the value is stable
        // across pre-/post-persist (the id is set on construction here, but
        // the constant is the documented JPA-entity convention).
        return Objects.hash(getClass());
    }
}
