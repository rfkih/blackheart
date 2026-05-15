package id.co.blackheart.dto.strategy;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StrategyRequirements {

    private boolean requireBiasTimeframe;
    private String biasInterval;

    private boolean requireRegimeSnapshot;
    private boolean requireVolatilitySnapshot;
    private boolean requireRiskSnapshot;
    private boolean requireMarketQualitySnapshot;

    /**
     * When true, the coordinator will populate previousFeatureStore
     * (same-timeframe FeatureStore from the candle immediately before the current one).
     */
    private boolean requirePreviousFeatureStore;

    public static StrategyRequirements defaults() {
        return StrategyRequirements.builder().build();
    }
}