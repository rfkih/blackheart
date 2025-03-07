package id.co.blackheart.model;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Setter
@Getter
@Table(name = "portfolio")
@IdClass(PortfolioId.class)  // Use composite key
public class Portfolio {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(nullable = false, length = 10)
    private String asset;

    @Column(nullable = false, length = 1)
    private String isActive;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal balance;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal locked;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime updatedAt;

}

