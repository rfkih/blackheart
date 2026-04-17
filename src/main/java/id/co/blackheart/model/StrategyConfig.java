package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.util.UUID;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "strategy_config")
public class StrategyConfig extends BaseEntity {

    @Id
    @Column(name = "strategy_config_id", nullable = false, updatable = false)
    private UUID strategyConfigId;

    @Column(name = "strategy_name", nullable = false, length = 100)
    private String strategyName;

    @Column(name = "interval_name", nullable = false, length = 20)
    private String intervalName;

    @Column(name = "symbol", length = 30)
    private String symbol;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

}