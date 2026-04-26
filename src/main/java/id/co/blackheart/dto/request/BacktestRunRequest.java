package id.co.blackheart.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

    @NotBlank
    @Pattern(regexp = "^(1m|3m|5m|15m|30m|1h|2h|4h|6h|8h|12h|1d|3d|1w|1M)$",
             message = "interval must be one of: 1m/3m/5m/15m/30m/1h/2h/4h/6h/8h/12h/1d/3d/1w/1M")
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

    private BigDecimal minNotional;
    private BigDecimal minQty;
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
    private Map<String, Map<String, Object>> strategyParamOverrides;

    /**
     * Phase 2A — max number of concurrent open trades across all strategies
     * in this backtest. When at the cap, new entry signals are skipped (the
     * combo doesn't get to over-allocate the book by all firing on the same
     * candle). Null / non-positive = no cap (legacy behaviour).
     */
    @DecimalMin(value = "0", inclusive = true)
    @DecimalMax(value = "20", inclusive = true)
    private Integer maxConcurrentStrategies;

    /**
     * Phase 2A — per-strategy capital allocation override for this run only,
     * as a percentage on the 0–100 scale. Key = strategy code, value =
     * allocation %. Strategies not in this map fall back to
     * {@code account_strategy.capital_allocation_pct}. Sum across the map is
     * NOT auto-validated against 100 — the user is free to over- or
     * under-allocate; the executor's balance check enforces the ceiling.
     */
    @Size(max = 10)
    private Map<String, BigDecimal> strategyAllocations;

    /**
     * Phase B2 — per-strategy interval for multi-timeframe runs.
     * Key = strategy code, value = interval string (e.g. "15m"). When
     * non-null, the coordinator loads N candle streams (one per unique
     * interval) and each strategy fires only on its own timeframe's bar
     * closes. When null/empty, all strategies share the run's primary
     * {@link #interval} field.
     */
    @Size(max = 10)
    private Map<String, @Pattern(regexp = "^(1m|3m|5m|15m|30m|1h|2h|4h|6h|8h|12h|1d|3d|1w|1M)$") String> strategyIntervals;
}
