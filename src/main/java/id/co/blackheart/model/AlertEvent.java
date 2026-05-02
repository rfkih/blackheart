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
 * Append-only operational alert log. Distinct from {@link AuditEvent}:
 * audit_event captures USER-actor security mutations; alert_event captures
 * SYSTEM-actor operational telemetry — kill-switch trips, ingest stalls,
 * verdict drift, P&amp;L deviation. Different audience, different volume.
 *
 * <p>Every call to {@code AlertService.raise} writes a row here, even when
 * dedup suppresses the outbound Telegram / email — so the timeline stays
 * complete and the flap rate is observable.
 */
@Entity
@Table(name = "alert_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertEvent {

    @Id
    @Column(name = "alert_event_id", nullable = false, updatable = false)
    private UUID alertEventId;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "kind", nullable = false, length = 60)
    private String kind;

    @Column(name = "message", nullable = false)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context")
    private JsonNode context;

    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    @Column(name = "suppressed", nullable = false)
    private boolean suppressed;

    @Column(name = "sent_telegram")
    private Boolean sentTelegram;

    @Column(name = "sent_email")
    private Boolean sentEmail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
