package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class BacktestTradeDetailResponse {
    private UUID id;
    private UUID backtestRunId;
    private String direction;
    private Long entryTime;
    private BigDecimal entryPrice;
    private Long exitTime;
    private BigDecimal exitPrice;
    private BigDecimal stopLossPrice;
    private BigDecimal tp1Price;
    private BigDecimal tp2Price;
    private BigDecimal quantity;
    private BigDecimal realizedPnl;
    private BigDecimal rMultiple;
    private List<BacktestPositionDetailResponse> positions;
}
