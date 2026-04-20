package id.co.blackheart.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRunRequest {

    /**
     * Default account strategy ID used when no per-strategy mapping is provided.
     * For single-strategy backtests this is the only ID needed.
     */
    private UUID accountStrategyId;

    /**
     * Per-strategy account strategy ID mapping for multi-strategy backtests.
     * Key = strategy code (e.g. "LSR"), value = accountStrategyId whose saved params to use.
     * Falls back to {@code accountStrategyId} for any strategy code not present in this map.
     */
    private Map<String, UUID> strategyAccountStrategyIds;

    private String strategyName;

    /**
     * Single-strategy code. If {@code strategyCodes} is also provided, this field is ignored.
     */
    private String strategyCode;

    /**
     * Ordered list of strategy codes for multi-strategy orchestrator mode.
     * When 2+ codes are supplied the orchestrator evaluates them in order and executes the
     * first entry signal; once a trade is open only the owning strategy manages it.
     * When only one code is supplied it is treated identically to {@code strategyCode}.
     */
    private List<String> strategyCodes;

    private String asset;
    private String interval;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private BigDecimal initialCapital;
    private BigDecimal riskPerTradePct;
    private BigDecimal feeRate;
    private BigDecimal slippageRate;

    private BigDecimal minNotional;
    private BigDecimal minQty;
    private BigDecimal qtyStep;

    private Boolean allowLong;
    private Boolean allowShort;
    private Integer maxOpenPositions;
}