package id.co.blackheart.projection;


import java.math.BigDecimal;
import java.util.UUID;

public interface TradePositionDailyAggregateProjection {

    UUID getAccountId();

    UUID getAccountStrategyId();

    BigDecimal getDailyRealizedPnlAmount();

    BigDecimal getDailyClosedNotional();

    Integer getClosedPositionCount();

    Integer getWinPositionCount();

    Integer getLossPositionCount();
}