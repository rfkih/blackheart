package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Setter
@Getter
@Table(
        name = "portfolio",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_portfolio_user_asset", columnNames = {"user_id", "asset"})
        }
)
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "asset", nullable = false, length = 10)
    private String asset;

    @Column(name = "is_active", nullable = false, length = 1)
    private String isActive;

    @Column(name = "balance", nullable = false, precision = 20, scale = 8)
    private BigDecimal balance;

    @Column(name = "locked", nullable = false, precision = 20, scale = 8)
    private BigDecimal locked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}