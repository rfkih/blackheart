package id.co.blackheart.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * One row per distinct error fingerprint that is currently open. The Logback
 * appender ({@code DbErrorAppender}) writes every ERROR-level event through
 * {@code ErrorIngestService}, which UPSERTs against the partial unique index
 * on {@code fingerprint WHERE status IN ('NEW','INVESTIGATING')}. A repeat
 * occurrence bumps {@link #occurrenceCount} and {@link #lastSeenAt} on the
 * existing open row instead of creating a new row; once the developer agent
 * marks the row {@code RESOLVED}, the next occurrence opens a new row — the
 * fix didn't hold.
 */
@Entity
@Table(name = "error_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorLog {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "error_id", nullable = false, updatable = false)
    private UUID errorId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** "trading" or "research" — both JVMs share the table. */
    @Column(name = "jvm", nullable = false, length = 20)
    private String jvm;

    @Column(name = "logger_name", nullable = false, length = 255)
    private String loggerName;

    @Column(name = "thread_name", length = 120)
    private String threadName;

    @Column(name = "level", nullable = false, length = 10)
    private String level;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "exception_class", length = 255)
    private String exceptionClass;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    /** Logback MDC at the time of the event (request id, user id, etc). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mdc", nullable = false)
    private JsonNode mdc;

    /**
     * Hash of (loggerName + exceptionClass + first 5 stack frames). Drives
     * the dedup UPSERT. Two errors with the same fingerprint are the same
     * bug as far as the developer agent is concerned.
     */
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "occurrence_count", nullable = false)
    private Integer occurrenceCount;

    /** CRITICAL / HIGH / MEDIUM / LOW — see {@code SeverityClassifier}. */
    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    /** NEW / INVESTIGATING / RESOLVED / IGNORED / WONT_FIX. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Optional pointer to a {@code code_review_finding} row created by the developer agent. */
    @Column(name = "developer_finding_id")
    private UUID developerFindingId;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 150)
    private String resolvedBy;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    /** Channels we have already notified for this fingerprint (e.g. {"telegram"}). */
    @Column(name = "notification_channels", columnDefinition = "text[]")
    private String[] notificationChannels;
}
