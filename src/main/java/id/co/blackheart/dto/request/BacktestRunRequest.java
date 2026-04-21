package id.co.blackheart.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class BacktestRunRequest {

    /**
     * Default account strategy ID used when no per-strategy mapping is provided.
     * For single-strategy backtests this is the only ID needed. The underlying
     * {@code backtest_run.account_strategy_id} column is NOT NULL, so callers
     * must always set this — even for multi-strategy runs (typically the first
     * id from {@link #strategyAccountStrategyIds}).
     */
    private UUID accountStrategyId;

    /**
     * Per-strategy account strategy ID mapping for multi-strategy backtests.
     * Key = strategy code (e.g. "LSR"), value = accountStrategyId whose saved params to use.
     * Falls back to {@link #accountStrategyId} for any strategy code not present in this map.
     */
    private Map<String, UUID> strategyAccountStrategyIds;

    private String strategyName;

    /**
     * Single-strategy code. If {@link #strategyCodes} is also provided, this field is ignored.
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

    /**
     * Per-strategy parameter overrides supplied from the backtest wizard (Step 2).
     * Key = strategy code (e.g. "LSR_V2"), value = diff map of param key → override value.
     * Only fields that differ from backend defaults are sent.
     * TODO: plumb into BacktestCoordinatorService so LsrParams.merge() / VcbParams.merge()
     *       picks them up at resolve time.
     */
    private Map<String, Map<String, Object>> strategyParamOverrides;
}
