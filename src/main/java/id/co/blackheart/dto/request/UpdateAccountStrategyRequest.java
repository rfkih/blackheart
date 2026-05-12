package id.co.blackheart.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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

    /** Enable/disable the regime gate (V43). Null leaves unchanged. */
    private Boolean regimeGateEnabled;

    /** Comma-separated allowed trend_regime values (e.g. "BULL,NEUTRAL"). Null leaves unchanged. */
    @Size(max = 100)
    private String allowedTrendRegimes;

    /** Comma-separated allowed volatility_regime values (e.g. "NORMAL,LOW"). Null leaves unchanged. */
    @Size(max = 100)
    private String allowedVolatilityRegimes;

    /** Enable/disable Kelly/bankroll sizing (V45). Null leaves unchanged. */
    private Boolean kellySizingEnabled;

    /**
     * Hard cap on the Kelly fraction [0.05, 1.00]. Null leaves unchanged.
     * Values below MIN_KELLY (0.05) are rejected — the floor is intentional.
     */
    @DecimalMin("0.05") @DecimalMax("1.00")
    private BigDecimal kellyMaxFraction;

    /** V55 — risk-based sizing toggle. Null leaves unchanged. */
    private Boolean useRiskBasedSizing;

    /**
     * V55 — per-trade risk as a fraction of cash balance, range (0, 0.20].
     * Null leaves unchanged.
     */
    @DecimalMin("0.0001") @DecimalMax("0.20")
    private BigDecimal riskPct;

    /**
     * Capital allocation as a percentage of account equity (0.01–100).
     * In direct-allocation mode this is the trade size. In risk-based mode
     * this acts as the notional position cap. Null leaves unchanged.
     */
    @DecimalMin("0.01") @DecimalMax("100.00")
    private BigDecimal capitalAllocationPct;

    /**
     * Allow long entries. Null leaves unchanged. At least one of allowLong /
     * allowShort must be true after the update — the service validates this.
     */
    private Boolean allowLong;

    /** Allow short entries. Null leaves unchanged. */
    private Boolean allowShort;
}
