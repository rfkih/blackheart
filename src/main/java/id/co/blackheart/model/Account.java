package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

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

    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(name = "api_secret", nullable = false)
    private String apiSecret;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}