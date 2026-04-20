package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class DailyPnlResponse {
    private String date;
    private BigDecimal realizedPnl;
    private Integer tradeCount;
}
