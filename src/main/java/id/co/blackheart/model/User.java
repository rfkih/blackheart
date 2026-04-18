package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Platform user — the top-level identity for every person who accesses the system.
 * Each user may own one or more {@link Account} records (exchange API keys).
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_email", columnNames = {"email"})
        },
        indexes = {
                @Index(name = "idx_users_status", columnList = "status"),
                @Index(name = "idx_users_email",  columnList = "email")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /** BCrypt-hashed password. Never store or log the raw value. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    /**
     * Coarse-grained role used for authorization.
     * Values: {@code SUPER_ADMIN} | {@code ADMIN} | {@code USER}
     */
    @Column(name = "role", nullable = false, length = 30)
    private String role;

    /**
     * Lifecycle state of the account.
     * Values: {@code ACTIVE} | {@code INACTIVE} | {@code SUSPENDED} | {@code PENDING_VERIFICATION}
     */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private Boolean emailVerified = false;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
