package id.co.blackheart.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response shape for unified {@code /api/v1/strategy-params/...} endpoints.
 *
 * <p>Returns the raw override map for now. Once the {@code StrategyEngine} ships,
 * an {@code effective} field will be added that exposes the merged
 * (archetype-defaults + overrides) view for UI rendering. The {@code overrides}
 * field stays stable so client code keying on it is unaffected.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrategyParamResponse {

    private UUID accountStrategyId;

    /** Archetype the strategy resolves to (e.g. {@code "mean_reversion_oscillator"}). */
    private String archetype;

    /** Strategy code bound to this account_strategy. */
    private String strategyCode;

    /** True when at least one override is set. */
    private boolean hasOverrides;

    /** Raw override map (only the keys the operator has explicitly set). */
    private Map<String, Object> overrides;

    /** Optimistic-lock version. {@code null} when no row exists yet. */
    private Long version;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
