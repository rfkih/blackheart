package id.co.blackheart.dto.strategy;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StrategyContext {
    private Users user;
    private String asset;
    private String interval;
    private MarketData marketData;
    private FeatureStore featureStore;
    private Trades activeTrade;
    private Portfolio portfolio;
}