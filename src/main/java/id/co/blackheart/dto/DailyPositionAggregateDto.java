package id.co.blackheart.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyPositionAggregateDto {

    private UUID accountId;
    private UUID accountStrategyId;

    private BigDecimal dailyRealizedPnlAmount;
    private BigDecimal dailyClosedNotional;

    private Integer closedPositionCount;
    private Integer winPositionCount;
    private Integer lossPositionCount;
}