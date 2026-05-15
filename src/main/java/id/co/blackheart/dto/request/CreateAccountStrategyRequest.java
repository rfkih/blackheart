package id.co.blackheart.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountStrategyRequest {

    @NotNull
    private UUID accountId;

    @NotBlank
    private String strategyCode;

    /**
     * Optional user-facing preset label. Multiple presets can exist for the
     * same (accountId, strategyCode, symbol, interval); only one at a time
     * is enabled. When omitted the service assigns a default like
     * "Preset 1", "Preset 2"…
     */
    @Size(max = 80)
    private String presetName;

    @NotBlank
    private String symbol;

    @NotBlank
    private String intervalName;

    @NotNull
    private Boolean allowLong;

    @NotNull
    private Boolean allowShort;

    @NotNull
    @Min(1)
    private Integer maxOpenPositions;

    @NotNull
    @DecimalMin("0.01")
    @DecimalMax("100.00")
    private BigDecimal capitalAllocationPct;

    @NotNull
    @Min(0)
    private Integer priorityOrder;

    /** Optional — defaults to false if omitted. */
    private Boolean enabled;

    /**
     * V55 — risk-based sizing toggle for LONG entries. When TRUE the strategy
     * sizes off {@link #riskPct} with {@link #capitalAllocationPct} acting as
     * the notional cap; when FALSE it uses the legacy direct-allocation
     * sizing. Optional — when omitted the service defaults to TRUE so new
     * presets adopt the unified risk model.
     */
    private Boolean useRiskBasedSizing;

    /**
     * V55 — per-trade risk as a fraction of cash balance. Range (0, 0.20].
     * Used only when {@link #useRiskBasedSizing} is TRUE. Optional — when
     * omitted the service defaults to 0.0500 (5%).
     */
    @DecimalMin("0.0001")
    @DecimalMax("0.20")
    private BigDecimal riskPct;
}
