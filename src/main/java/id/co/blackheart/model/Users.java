package id.co.blackheart.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Users {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 1)
    private String isActive;

    @Column(nullable = false, length = 3)
    private String exchange;

    @Column(precision = 5, scale = 2)
    private BigDecimal riskAmount;

    @Column(precision = 5, scale = 2)
    private BigDecimal confidence;

    @Column(nullable = false, precision = 20, scale = 12)
    private BigDecimal takeProfit;

    @Column(nullable = false, precision = 20, scale = 12)
    private BigDecimal stopLoss;

    @Column(nullable = false)
    private String apiKey;

    @Column(nullable = false)
    private String apiSecret;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime updatedAt;
}
