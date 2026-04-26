package id.co.blackheart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial-update request for an existing account strategy.
 *
 * <p>All fields are optional — null fields are left unchanged. Liveness is
 * toggled via the dedicated {@code /:id/activate} and {@code /:id/deactivate}
 * endpoints to keep the sibling-deactivation / open-trade guards on a single
 * code path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAccountStrategyRequest {

    /**
     * Optional candle-interval change. Refuses if the strategy has open
     * trades (mid-position TF change is unsafe). Null leaves unchanged.
     */
    @Size(max = 20)
    private String intervalName;

    /**
     * Optional priority order. Lower values win the orchestrator's
     * fan-out tiebreak when multiple strategies on the same interval
     * signal entry simultaneously. Null leaves unchanged.
     */
    @Min(1) @Max(99)
    private Integer priorityOrder;
}
