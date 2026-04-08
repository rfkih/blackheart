package id.co.blackheart.dto.strategy;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class BaseStrategyContext {

    /**
     * Identity
     */
    private Account account;
    private AccountStrategy accountStrategy;

    private String asset;
    private String interval;

    /**
     * Raw market state for current timeframe
     */
    private MarketData marketData;
    private FeatureStore featureStore;

    /**
     * Strategy-facing position state
     */
    private PositionSnapshot positionSnapshot;
    private Boolean hasOpenPosition;
    private Integer openPositionCount;

    /**
     * Optional generic metadata for execution layer / coordinator
     * Example:
     * - live: activeTradeId
     * - backtest: backtestRunId
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
     * Risk input before enrichment
     */
    private BigDecimal riskPerTradePct;

    /**
     * Optional diagnostics / loader metadata
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
        if (value == null || !type.isInstance(value)) {
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