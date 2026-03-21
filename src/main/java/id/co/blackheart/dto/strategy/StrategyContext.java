package id.co.blackheart.dto.strategy;

import id.co.blackheart.model.*;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class StrategyContext {
    private Users user;
    private UUID userStrategyId;
    private String asset;
    private String interval;
    private Trades activeTrade;
    private MarketData marketData;
    private FeatureStore featureStore;
    private boolean allowLong;
    private boolean allowShort;
    private MarketData biasMarketData;
    private FeatureStore biasFeatureStore;
    private PositionSnapshot positionSnapshot;
    private Portfolio portfolio;
}