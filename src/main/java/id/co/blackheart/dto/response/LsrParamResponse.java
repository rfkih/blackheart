package id.co.blackheart.dto.response;

import id.co.blackheart.dto.lsr.LsrParams;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response envelope for LSR strategy parameter queries.
 *
 * <p>Returns both the <em>effective</em> resolved params (defaults merged with overrides)
 * and the raw overrides map so clients can see exactly what they have customised.
 */
@Data
@Builder
public class LsrParamResponse {

    private UUID accountStrategyId;

    /** Whether this account strategy has any custom overrides stored. */
    private boolean hasCustomParams;

    /** The raw overrides map stored in DB — may be empty if using all defaults. */
    private Map<String, Object> overrides;

    /** Effective resolved values after merging defaults with overrides. */
    private LsrParams effectiveParams;

    /** DB row version — used for optimistic-locking on updates. */
    private Long version;

    private LocalDateTime updatedAt;

    // ── Convenience factory ───────────────────────────────────────────────────────

    public static LsrParamResponse ofDefaults() {
        return LsrParamResponse.builder()
                .accountStrategyId(null)
                .hasCustomParams(false)
                .overrides(Map.of())
                .effectiveParams(LsrParams.defaults())
                .version(null)
                .updatedAt(null)
                .build();
    }
}
