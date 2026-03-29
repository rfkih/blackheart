package id.co.blackheart.dto.strategy;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class EnrichedStrategyContext {

    /**
     * Identity
     */
    private Account account;
    private AccountStrategy accountStrategy;
    private String asset;
    private String interval;

    /**
     * Current timeframe market state
     */
    private MarketData marketData;
    private FeatureStore featureStore;

    /**
     * Higher/lower timeframe optional supporting state
     */
    private MarketData biasMarketData;
    private FeatureStore biasFeatureStore;

    /**
     * Quant snapshots
     */
    private RegimeSnapshot regimeSnapshot;
    private VolatilitySnapshot volatilitySnapshot;
    private RiskSnapshot riskSnapshot;
    private MarketQualitySnapshot marketQualitySnapshot;

    /**
     * Strategy-facing position state.
     * This should be the main source used by StrategyExecutor.
     */
    private PositionSnapshot positionSnapshot;
    private Boolean hasOpenPosition;
    private Integer openPositionCount;

    /**
     * Optional generic metadata for execution layers.
     * Use this instead of binding strategy logic to live/backtest entity classes.
     */
    private Map<String, Object> executionMetadata;

    /**
     * Balances
     */
    private BigDecimal cashBalance;
    private BigDecimal assetBalance;

    /**
     * Permissions / controls
     */
    private Boolean allowLong;
    private Boolean allowShort;
    private Integer maxOpenPositions;
    private Integer currentOpenTradeCount;

    /**
     * Parsed strategy config
     */
    private StrategyRuntimeConfig runtimeConfig;

    /**
     * Optional diagnostics / extra computed values
     */
    private Map<String, Object> diagnostics;

    public boolean isLongAllowed() {
        return Boolean.TRUE.equals(allowLong);
    }

    public boolean isShortAllowed() {
        return Boolean.TRUE.equals(allowShort);
    }

    public boolean hasTradablePosition() {
        return Boolean.TRUE.equals(hasOpenPosition)
                && positionSnapshot != null
                && Boolean.TRUE.equals(positionSnapshot.isHasOpenPosition());
    }

    public int safeOpenPositionCount() {
        return openPositionCount == null ? 0 : openPositionCount;
    }

    @SuppressWarnings("unchecked")
    public <T> T getExecutionMetadata(String key, Class<T> type) {
        if (executionMetadata == null || key == null || type == null) {
            return null;
        }

        Object value = executionMetadata.get(key);
        if (value == null) {
            return null;
        }

        if (!type.isInstance(value)) {
            return null;
        }

        return (T) value;
    }

    public Object getDiagnostic(String key) {
        if (diagnostics == null || key == null) {
            return null;
        }
        return diagnostics.get(key);
    }
}