package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/backtest/{runId}/activate-strategy}.
 * Ties a completed backtest run to a user-owned account strategy: saves the
 * run's parameter snapshot as a new active preset and enables the strategy.
 */
@Data
public class ActivateBacktestStrategyRequest {

    /** Strategy code to extract from the run's configSnapshot (e.g. "LSR", "VCB"). */
    @NotBlank
    private String strategyCode;

    /** User's own account strategy that will receive the preset + be enabled. */
    @NotNull
    private UUID accountStrategyId;

    /**
     * Optional label for the saved preset.
     * Auto-generated as "Backtest {runId[:8]} · {date}" when blank.
     */
    private String presetName;
}
