package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BacktestEquityPointResponse {
    private Long ts;
    private BigDecimal equity;
    private BigDecimal drawdown;
    private BigDecimal drawdownPct;
}
