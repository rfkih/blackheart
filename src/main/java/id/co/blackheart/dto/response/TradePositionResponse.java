package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class TradePositionResponse {
    private UUID id;
    private UUID tradeId;
    private String type;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private Long exitTime;
    private BigDecimal exitPrice;
    private String exitReason;
    private BigDecimal feeUsdt;
    private BigDecimal realizedPnl;
}
