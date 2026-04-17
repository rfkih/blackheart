package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
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
public class Portfolio extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "asset", nullable = false, length = 10)
    private String asset;

    @Column(name = "is_active", nullable = false, length = 1)
    private String isActive;

    @Column(name = "balance", nullable = false, precision = 20, scale = 8)
    private BigDecimal balance;

    @Column(name = "locked", nullable = false, precision = 20, scale = 8)
    private BigDecimal locked;

}