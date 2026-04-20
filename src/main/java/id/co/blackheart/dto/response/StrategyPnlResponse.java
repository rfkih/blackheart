package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class StrategyPnlResponse {
    private String strategyCode;
    private BigDecimal realizedPnl;
    private Integer tradeCount;
    private BigDecimal winRate;
}
