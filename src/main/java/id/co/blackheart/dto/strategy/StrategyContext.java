package id.co.blackheart.dto.strategy;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class StrategyContext {

    private Users user;
    private String asset;
    private String interval;

    private UUID userStrategyId;
    private String strategyCode;

    private boolean allowLong;
    private boolean allowShort;

    private BigDecimal cashBalance;
    private BigDecimal assetBalance;
    private BigDecimal riskPerTradePct;

    private Integer maxOpenPositions;
    private Integer currentOpenTradeCount;

    private MarketData marketData;
    private FeatureStore featureStore;

    private MarketData biasMarketData;
    private FeatureStore biasFeatureStore;

    private Trades activeTrade;
    private List<Trades> activeTrades;

    private PositionSnapshot positionSnapshot;
    private List<TradePosition> activeTradePositions;
}