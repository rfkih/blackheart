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
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(nullable = false, length = 10)
    private String asset;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal balance;

    @Column(nullable = false)
    private LocalDateTime lastUpdated = LocalDateTime.now();
}
