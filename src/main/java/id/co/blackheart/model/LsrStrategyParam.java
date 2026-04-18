package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists per-account-strategy parameter overrides for the LSR strategy.
 *
 * <p>Only account strategies that deviate from defaults have a row here.
 * A missing row means "use all defaults". The {@code param_overrides} JSONB column
 * stores only the fields the user has explicitly set — the service layer merges them
 * with {@link id.co.blackheart.dto.lsr.LsrParams#defaults()} at read time.
 *
 * <p>Primary key is {@code account_strategy_id} — one param set per strategy config.
 */
@Entity
@Table(name = "lsr_strategy_param",
        indexes = {
                @Index(name = "idx_lsr_param_account_strategy", columnList = "account_strategy_id", unique = true)
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsrStrategyParam extends BaseEntity {

    /** Same as the account_strategy_id FK — serves as PK for 1-to-1 semantics. */
    @Id
    @Column(name = "account_strategy_id", nullable = false, updatable = false)
    private UUID accountStrategyId;

    /**
     * Partial override map — keys are camelCase param names (matching {@link id.co.blackheart.dto.lsr.LsrParams}
     * field names), values are numbers or strings castable to the target type.
     * Stored as {@code jsonb} in PostgreSQL.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_overrides", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> paramOverrides = new HashMap<>();

    /**
     * Optimistic-lock version. Prevents concurrent PUT/PATCH from silently overwriting each other.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
