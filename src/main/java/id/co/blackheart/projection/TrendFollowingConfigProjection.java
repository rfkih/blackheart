package id.co.blackheart.projection;

import java.math.BigDecimal;
import java.util.UUID;

public interface TrendFollowingConfigProjection {

    UUID getStrategyConfigId();
    UUID getTrendFollowingConfigDetailId();

    String getStrategyName();
    String getIntervalName();
    String getSymbol();
    String getStatus();
    Integer getVersion();
    Boolean getEnabled();

    BigDecimal getMinAdx();
    BigDecimal getMinEfficiencyRatio();
    BigDecimal getMinRelativeVolume();

    BigDecimal getStopAtrMultiplier();
    BigDecimal getTakeProfitAtrMultiplier();
    BigDecimal getTrailingAtrMultiplier();

    Boolean getAllowLong();
    Boolean getAllowShort();

    Boolean getAllowBreakoutEntry();
    Boolean getAllowPullbackEntry();
    Boolean getAllowBiasEntry();
}
