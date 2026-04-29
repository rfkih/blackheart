package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial-update request for an existing account strategy.
 *
 * <p>Currently supports changing the candle interval only. Liveness is toggled
 * via the dedicated {@code /:id/activate} and {@code /:id/deactivate}
 * endpoints to keep the sibling-deactivation / open-trade guards on a single
 * code path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAccountStrategyRequest {

    @NotBlank
    private String intervalName;
}
