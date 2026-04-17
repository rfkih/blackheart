package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Standard audit-trail base for every application-managed entity.
 *
 * <p>Provides four fields:
 * <ul>
 *   <li>{@code created_time} — set once by Hibernate on INSERT</li>
 *   <li>{@code created_by}   — identity of the creator; populated by the service layer</li>
 *   <li>{@code updated_time} — refreshed by Hibernate on every UPDATE</li>
 *   <li>{@code updated_by}   — identity of the last modifier; populated by the service layer</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   {@literal @}Entity
 *   {@literal @}Table(name = "my_table")
 *   public class MyEntity extends BaseEntity { ... }
 * </pre>
 *
 * <p>Excluded entities: {@code MarketData} and {@code FeatureStore}
 * (Java-owned market-data tables that follow their own schema contract).
 */
@Getter
@Setter
@MappedSuperclass
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEntity {

    @CreationTimestamp
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @UpdateTimestamp
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;

    @Column(name = "updated_by", length = 150)
    private String updatedBy;
}
