package id.co.blackheart.dto.strategy;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

@Builder
@Data
public class StrategyRuntimeConfig {

    /**
     * Strategy identity
     */
    private String strategyCode;
    private String strategyVersion;

    /**
     * Raw config loaded from DB (later from JSONB).
     * Keep this as the source of truth for strategy-specific parameters.
     */
    @Builder.Default
    private Map<String, Object> rawConfig = Collections.emptyMap();

    /**
     * Common cross-strategy controls
     */
    private BigDecimal minSignalScore;
    private BigDecimal minRegimeScore;
    private BigDecimal maxJumpRiskScore;
    private BigDecimal defaultRiskMultiplier;
    private BigDecimal maxRiskMultiplier;
    private BigDecimal minRiskMultiplier;

    /**
     * Trade permissions / controls
     */
    private Boolean allowLong;
    private Boolean allowShort;
    private Integer maxOpenPositions;
    private Integer cooldownBars;
    private Integer maxHoldingBars;

    /**
     * Global risk / sizing controls
     */
    private BigDecimal riskPerTradePct;
    private BigDecimal maxPositionSize;
    private BigDecimal minPositionSize;
    private BigDecimal maxNotionalSize;

    /**
     * Global volatility / market filters
     */
    private BigDecimal maxAtrPercentile;
    private BigDecimal minAtrPercentile;
    private BigDecimal minLiquidityScore;
    private BigDecimal minVolumeScore;

    /**
     * Helper methods
     */
    public Object get(String key) {
        return rawConfig == null ? null : rawConfig.get(key);
    }

    public String getString(String key) {
        Object value = get(key);
        return value == null ? null : String.valueOf(value);
    }

    public Integer getInteger(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    public Long getLong(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    public Boolean getBoolean(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    public BigDecimal getBigDecimal(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    public boolean isLongAllowed() {
        return Boolean.TRUE.equals(allowLong);
    }

    public boolean isShortAllowed() {
        return Boolean.TRUE.equals(allowShort);
    }
}