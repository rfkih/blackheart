package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class BacktestPositionDetailResponse {
    private UUID id;
    private String type;
    private BigDecimal quantity;
    private Long exitTime;
    private BigDecimal exitPrice;
    private String exitReason;
    private BigDecimal realizedPnl;
}
