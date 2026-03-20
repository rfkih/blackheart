package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_strategy")
public class UserStrategy {

    @Id
    @Column(name = "user_strategy_id", nullable = false, updatable = false)
    private UUID userStrategyId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "strategy_definition_id", nullable = false)
    private UUID strategyDefinitionId;

    @Column(name = "strategy_code", nullable = false, length = 100)
    private String strategyCode;

    @Column(name = "symbol", nullable = false, length = 30)
    private String symbol;

    @Column(name = "interval_name", nullable = false, length = 20)
    private String intervalName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "allow_long", nullable = false)
    private Boolean allowLong;

    @Column(name = "allow_short", nullable = false)
    private Boolean allowShort;

    @Column(name = "max_open_positions", nullable = false)
    private Integer maxOpenPositions;

    @Column(name = "capital_allocation_pct", nullable = false, precision = 8, scale = 4)
    private BigDecimal capitalAllocationPct;

    @Column(name = "priority_order", nullable = false)
    private Integer priorityOrder;

    @Column(name = "current_status", nullable = false, length = 30)
    private String currentStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}