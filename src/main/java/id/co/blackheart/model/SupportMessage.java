package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * In-app contact-form submission. Users post these from Settings → Help &
 * support; admins read them on the {@code /admin/inbox} page. The row
 * stamps the submitting user's id and email at submit time so admins can
 * triage even if the user's profile changes later.
 */
@Entity
@Table(name = "support_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportMessage {

    @Id
    @Column(name = "support_message_id", nullable = false, updatable = false)
    private UUID supportMessageId;

    /** UUID of the user who submitted. Always set — endpoint is auth-gated. */
    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    /**
     * Snapshot of the user's email at submit time. Stored separately from
     * the user row so a later email change doesn't rewrite history.
     */
    @Column(name = "from_email", nullable = false, length = 320)
    private String fromEmail;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    /** Free-form message body. Capped at 5000 chars by request validation. */
    @Column(name = "body", nullable = false, length = 5000)
    private String body;

    /**
     * Diagnostic snapshot the frontend captured when the user clicked
     * Submit (app version, page, user-agent). Stored as a single string
     * because we never query it relationally — it's forensic context only.
     */
    @Column(name = "diagnostic", length = 2000)
    private String diagnostic;

    /**
     * Lifecycle: NEW (just submitted), READ (admin has seen it), RESOLVED
     * (admin marked it done). Kept as a string column so admins can extend
     * the vocabulary without a schema change.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Stamped on first transition out of NEW. Null while unread. */
    @Column(name = "read_at")
    private LocalDateTime readAt;
}
