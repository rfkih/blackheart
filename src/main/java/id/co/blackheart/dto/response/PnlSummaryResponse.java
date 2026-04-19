package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PnlSummaryResponse {
    private String period;
    private BigDecimal realizedPnl;
    private BigDecimal unrealizedPnl;
    private BigDecimal totalPnl;
    private Integer tradeCount;
    private BigDecimal winRate;
    private Integer openCount;
}
