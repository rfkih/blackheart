package id.co.blackheart.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    @NotNull
    private UUID accountStrategyId;

    /**
     * Per-strategy account strategy ID mapping for multi-strategy backtests.
     * Key = strategy code (e.g. "LSR"), value = accountStrategyId whose saved params to use.
     * Falls back to {@link #accountStrategyId} for any strategy code not present in this map.
     */
    @Size(max = 10)
    private Map<String, UUID> strategyAccountStrategyIds;

    /**
     * Per-strategy pinned preset IDs. Key = uppercase strategy code,
     * value = {@code strategy_param.param_id}. Lets a run lock onto a specific
     * preset row (including a soft-deleted historical one) instead of "whatever
     * preset is active right now". Falls back to the active preset for the
     * account_strategy when a strategy code is absent from this map. Optional;
     * the wizard does not need to send it for the existing form to keep working.
     */
    @Size(max = 10)
    private Map<String, UUID> strategyParamIds;

    @Size(max = 150)
    private String strategyName;

    /**
     * Single-strategy code. If {@link #strategyCodes} is also provided, this field is ignored.
     */
    @Size(max = 60)
    private String strategyCode;

    /**
     * Ordered list of strategy codes for multi-strategy orchestrator mode.
     * When 2+ codes are supplied the orchestrator evaluates them in order and executes the
     * first entry signal; once a trade is open only the owning strategy manages it.
     * When only one code is supplied it is treated identically to {@code strategyCode}.
     */
    @Size(max = 10)
    private List<@NotBlank @Size(max = 60) String> strategyCodes;

    @NotBlank
    @Size(max = 30)
    private String asset;

    /**
     * Primary interval for the backtest. Restricted to 5m/15m/1h/4h —
     * the engine ticks on a 5m monitor candle so anything finer would miss
     * bar closes, and timeframes coarser than 4h aren't part of the
     * supported strategy set. Live trading still supports 1m via WebSocket;
     * this restriction is backtest-only.
     */
    @NotBlank
    @Pattern(regexp = "^(5m|15m|1h|4h)$",
             message = "interval must be one of: 5m / 15m / 1h / 4h")
    private String interval;

    @NotNull
    private LocalDateTime startTime;

    @NotNull
    private LocalDateTime endTime;

    /**
     * Bounded to keep a single request from driving a runaway backtest. Upper
     * bound of 10M USDT is well beyond any legitimate trading-account size and
     * catches accidental overflow numbers.
     */
    @NotNull
    @DecimalMin(value = "0.01", message = "initialCapital must be at least 0.01 USDT")
    @DecimalMax(value = "10000000", message = "initialCapital must be at most 10,000,000 USDT")
    private BigDecimal initialCapital;

    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "100.0", inclusive = true)
    private BigDecimal riskPerTradePct;

    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "0.1", inclusive = true, message = "feeRate cannot exceed 10%")
    private BigDecimal feeRate;

    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "0.1", inclusive = true, message = "slippageRate cannot exceed 10%")
    private BigDecimal slippageRate;

    /**
     * Phase 0 funding-cost stub. Basis points per 8h Binance funding period;
     * applied flat across the run. Default {@code null} → 0 (legacy parity).
     * Bounded ±50 bps/8h — beyond that you're in distressed-market territory
     * where a single rate stub is the wrong tool anyway (use Phase 4 history).
     */
    @DecimalMin(value = "-50.0", inclusive = true,
                message = "fundingRateBpsPer8h cannot be below -50 bps/8h")
    @DecimalMax(value = "50.0", inclusive = true,
                message = "fundingRateBpsPer8h cannot exceed 50 bps/8h")
    private BigDecimal fundingRateBpsPer8h;

    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "100000", inclusive = true)
    private BigDecimal minNotional;

    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "100000", inclusive = true)
    private BigDecimal minQty;

    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "100000", inclusive = true)
    private BigDecimal qtyStep;

    private Boolean allowLong;
    private Boolean allowShort;
    private Integer maxOpenPositions;

    /**
     * Per-strategy parameter overrides supplied from the backtest wizard (Step 2).
     * Key = strategy code (e.g. "LSR_V2"), value = diff map of param key → override value.
     * Only fields that differ from backend defaults are sent. The outer map is
     * capped to limit blast radius — a malicious client can't flood the server
     * with thousands of arbitrary strategy codes.
     */
    @Size(max = 10)
    private Map<String, @Size(max = 60) Map<String, Object>> strategyParamOverrides;

    /**
     * Max concurrent open trades across all strategies in this backtest. When
     * at the cap, new entry signals are skipped so the book isn't over-allocated
     * by all strategies firing on the same candle. Null/non-positive = no cap.
     */
    @Min(0)
    @Max(20)
    private Integer maxConcurrentStrategies;

    /**
     * Per-strategy capital allocation override for this run only, as a
     * percentage on the 0–100 scale. Key = strategy code, value = allocation %.
     * Strategies not in this map fall back to
     * {@code account_strategy.capital_allocation_pct}. Sum is NOT validated
     * against 100 — the executor's balance check enforces the ceiling.
     */
    @Size(max = 10)
    private Map<String, BigDecimal> strategyAllocations;

    /**
     * Per-strategy interval for multi-timeframe runs. Key = strategy code,
     * value = interval string (e.g. "15m"). When non-null, the coordinator
     * loads one candle stream per unique interval and each strategy fires
     * only on its own bar closes. Null/empty = all strategies share the
     * run's primary {@link #interval}.
     */
    @Size(max = 10)
    private Map<String,
            @Pattern(regexp = "^(5m|15m|1h|4h)$",
                     message = "per-strategy interval must be one of: 5m / 15m / 1h / 4h")
            String> strategyIntervals;

    /**
     * Origin tag — {@code USER} (default) or {@code RESEARCHER}. The
     * autonomous research-orchestrator stamps {@code RESEARCHER}; the
     * frontend wizard never sets it. The service defaults missing/blank
     * values to {@code USER}. Pattern is permissive so the column's CHECK
     * constraint catches typos at the DB layer.
     */
    @Pattern(regexp = "^(USER|RESEARCHER)$",
             message = "triggeredBy must be USER or RESEARCHER")
    private String triggeredBy;
}
