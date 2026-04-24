package id.co.blackheart.model;

import id.co.blackheart.converter.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "accounts")
public class Account extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /**
     * FK → users.user_id.
     * The platform user who owns this exchange API account.
     * Nullable to preserve backwards-compatibility with rows created before the
     * users table was introduced; should be non-null for all new rows.
     */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "is_active", nullable = false, length = 1)
    private String isActive;

    @Column(name = "exchange", nullable = false, length = 3)
    private String exchange;

    @Column(name = "risk_amount", precision = 5, scale = 2)
    private BigDecimal riskAmount;

    @Column(name = "confidence", precision = 5, scale = 2)
    private BigDecimal confidence;

    @Column(name = "take_profit", nullable = false, precision = 20, scale = 12)
    private BigDecimal takeProfit;

    @Column(name = "stop_loss", nullable = false, precision = 20, scale = 12)
    private BigDecimal stopLoss;

    /**
     * Binance API key. Encrypted at rest via {@link EncryptedStringConverter}
     * (AES-256-GCM); DB column type should be widened to support the base64-encoded
     * ciphertext envelope (~64 chars for a 40-char key).
     */
    @Column(name = "api_key", nullable = false, length = 1024)
    @Convert(converter = EncryptedStringConverter.class)
    private String apiKey;

    /**
     * Binance API secret. Encrypted at rest via {@link EncryptedStringConverter}.
     */
    @Column(name = "api_secret", nullable = false, length = 1024)
    @Convert(converter = EncryptedStringConverter.class)
    private String apiSecret;

}