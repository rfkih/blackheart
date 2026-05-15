package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/strategy-params} — create a new saved
 * preset for an {@code account_strategy}.
 */
@Data
@NoArgsConstructor
public class StrategyParamCreateRequest {

    @NotNull(message = "accountStrategyId is required")
    private UUID accountStrategyId;

    @NotBlank(message = "name is required")
    @Size(max = 120, message = "name max 120 chars")
    private String name;

    @NotNull(message = "overrides map is required (use empty {} for defaults)")
    private Map<String, Object> overrides = new HashMap<>();

    /**
     * When true, the new preset becomes the account_strategy's active preset
     * and any existing active preset is deactivated atomically.
     */
    private boolean activate = false;

    /**
     * Optional — id of the {@code backtest_run} this preset was derived from.
     * Sent by the frontend's Re-run-with-params "Save to library" button so
     * the saved-preset list can show the originating run. Null when the
     * preset isn't tied to a specific backtest.
     */
    private UUID sourceBacktestRunId;
}
