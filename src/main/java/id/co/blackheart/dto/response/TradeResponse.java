package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class TradeResponse {
    private UUID id;
    private UUID accountId;
    private UUID accountStrategyId;
    private String strategyCode;
    private String symbol;
    private String direction;
    private String status;
    private Long entryTime;
    private BigDecimal entryPrice;
    private Long exitTime;
    private BigDecimal exitAvgPrice;
    private BigDecimal stopLossPrice;
    private BigDecimal tp1Price;
    private BigDecimal tp2Price;
    private BigDecimal quantity;
    private BigDecimal realizedPnl;
    private BigDecimal unrealizedPnl;
    private BigDecimal feeUsdt;
    private BigDecimal markPrice;
    private BigDecimal unrealizedPnlPct;
    private List<TradePositionResponse> positions;
}
