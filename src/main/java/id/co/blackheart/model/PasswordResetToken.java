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
 * Single-use password-reset token. The raw token is delivered to the user
 * out-of-band (today: logged for admin retrieval; tomorrow: emailed) and
 * presented back at confirm-time. Lookup is by the token itself, which is
 * stored as the row's primary key.
 *
 * <p>Tokens are one-shot — {@link #usedAt} is stamped on first successful
 * confirm and any subsequent attempt with the same token is rejected.
 * Tokens also expire after a fixed TTL ({@code expiresAt}); requests for
 * stale tokens 410-Gone, not 404, so the UX can distinguish "wrong link"
 * from "old link".
 */
@Entity
@Table(name = "password_reset_token")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    /**
     * The token itself — opaque, URL-safe, 32+ bytes of randomness. We store
     * it as the primary key so lookups are O(1) and we don't need a
     * separate id column.
     */
    @Id
    @Column(name = "token", nullable = false, updatable = false, length = 128)
    private String token;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Stamped on first successful confirm. Null = unused. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
