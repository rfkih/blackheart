package id.co.blackheart.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request body for {@code PATCH /api/v1/strategy-params/{paramId}} — update
 * the mutable fields of an existing saved preset. Both fields are nullable;
 * a {@code null} field is left unchanged.
 *
 * <p>Activation flips are NOT done through this endpoint — use
 * {@code POST /:paramId/activate} or {@code /deactivate} instead so the
 * "exactly one active per account_strategy" invariant is enforced atomically.
 */
@Data
@NoArgsConstructor
public class StrategyParamUpdateRequest {

    @Size(max = 120, message = "name max 120 chars")
    private String name;

    /** Replaces the entire override map when present. */
    private Map<String, Object> overrides;
}
