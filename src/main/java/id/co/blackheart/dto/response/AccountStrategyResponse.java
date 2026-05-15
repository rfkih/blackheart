package id.co.blackheart.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class AccountStrategyResponse {

    @JsonProperty("id")
    private UUID accountStrategyId;
    private UUID accountId;
    private UUID strategyDefinitionId;
    private String strategyCode;
    private String presetName;
    private String symbol;
    @JsonProperty("interval")
    private String intervalName;
    private Boolean enabled;
    /** Paper-trade flag: when true the strategy emits real signals but
     *  OPEN_LONG/OPEN_SHORT are diverted to {@code paper_trade_run}. Combined
     *  with {@code enabled} this lets the frontend derive the promotion state
     *  (RESEARCH / PAPER_TRADE / PROMOTED / DEMOTED). */
    private Boolean simulated;
    private Boolean allowLong;
    private Boolean allowShort;
    private Integer maxOpenPositions;
    private BigDecimal capitalAllocationPct;
    private Integer priorityOrder;
    @JsonProperty("createdAt")
    private LocalDateTime createdTime;
    @JsonProperty("updatedAt")
    private LocalDateTime updatedTime;
    /** Drawdown kill-switch state — surfaced so the strategy detail page can
     *  render a clear "tripped" badge and the re-arm button. */
    private BigDecimal ddKillThresholdPct;
    private Boolean isKillSwitchTripped;
    private LocalDateTime killSwitchTrippedAt;
    private String killSwitchReason;
    /** Regime gate (V43) — gate live entries on FeatureStore regime columns. */
    private Boolean regimeGateEnabled;
    private String allowedTrendRegimes;
    private String allowedVolatilityRegimes;
    /** Kill-switch entry gate (V62). When true, is_kill_switch_tripped blocks new entries. */
    private Boolean killSwitchGateEnabled;
    /** Correlation / concentration gate (V62). When true, CorrelationGuardService runs. */
    private Boolean correlationGateEnabled;
    /** Account-level concurrent-position cap gate (V62). When true, account max_concurrent_* applies. */
    private Boolean concurrentCapGateEnabled;
    /** Kelly/bankroll sizing (V45) — PSR-discounted half-Kelly multiplier. */
    private Boolean kellySizingEnabled;
    private BigDecimal kellyMaxFraction;
    /** Risk-based sizing toggle (V55). When TRUE, LONG entries on legacy
     *  strategies size off {@link #riskPct} with {@link #capitalAllocationPct}
     *  acting as the notional cap. */
    private Boolean useRiskBasedSizing;
    /** Per-trade risk as a fraction of cash balance, range (0, 0.20]. Used
     *  only when {@link #useRiskBasedSizing} is TRUE. */
    private BigDecimal riskPct;
    /** Tenant visibility (V54): PRIVATE = listed only to the owner; PUBLIC = listed to all users for browse-and-clone. */
    private String visibility;
    /** True iff the calling user owns the account this strategy belongs to. Drives "edit/delete vs clone" UI branching. */
    private Boolean ownedByCurrentUser;
    /** Display label for the owning account: "You", "Research Agent", or another tenant's account username. */
    private String ownerLabel;
}
