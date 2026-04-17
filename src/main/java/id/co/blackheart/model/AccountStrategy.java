package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "account_strategy")
public class AccountStrategy extends BaseEntity {

    @Id
    @Column(name = "account_strategy_id", nullable = false, updatable = false)
    private UUID accountStrategyId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

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

}