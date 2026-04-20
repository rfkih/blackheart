package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "vcb_strategy_param")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VcbStrategyParam extends BaseEntity {

    @Id
    @Column(name = "account_strategy_id", nullable = false, updatable = false)
    private UUID accountStrategyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_overrides", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> paramOverrides = new HashMap<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
