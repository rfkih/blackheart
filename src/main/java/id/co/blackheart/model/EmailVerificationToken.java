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
 * Single-use email-verification token. Same shape and lifecycle as
 * {@link PasswordResetToken}: issued at register-time (or via a resend
 * endpoint), delivered out-of-band, consumed once via /verify-email.
 *
 * <p>Until SMTP / SES / Mailgun is wired in, the verification URL is logged
 * at {@code WARN} so an operator can convey it to the user manually. The
 * register endpoint still authenticates the user immediately — verification
 * is a sticky reminder via dashboard banner, not a hard gate.
 */
@Entity
@Table(name = "email_verification_token")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationToken {

    @Id
    @Column(name = "token", nullable = false, updatable = false, length = 128)
    private String token;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
