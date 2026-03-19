package id.co.blackheart.dto.strategy;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendFollowingStrategyConfigDto {

    private UUID strategyConfigId;
    private UUID trendFollowingConfigDetailId;

    private String strategyName;
    private String intervalName;
    private String symbol;
    private String status;
    private Integer version;
    private Boolean enabled;

    private BigDecimal minAdx;
    private BigDecimal minEfficiencyRatio;
    private BigDecimal minRelativeVolume;

    private BigDecimal stopAtrMultiplier;
    private BigDecimal takeProfitAtrMultiplier;
    private BigDecimal trailingAtrMultiplier;

    private Boolean allowLong;
    private Boolean allowShort;

    private Boolean allowBreakoutEntry;
    private Boolean allowPullbackEntry;
    private Boolean allowBiasEntry;
}
