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

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "strategy_config")
public class StrategyConfig {

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

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}