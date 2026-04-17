package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "strategy_definition")
public class StrategyDefinition extends BaseEntity {

    @Id
    @Column(name = "strategy_definition_id", nullable = false, updatable = false)
    private UUID strategyDefinitionId;

    @Column(name = "strategy_code", nullable = false, unique = true, length = 100)
    private String strategyCode;

    @Column(name = "strategy_name", nullable = false, length = 200)
    private String strategyName;

    @Column(name = "strategy_type", nullable = false, length = 100)
    private String strategyType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

}
