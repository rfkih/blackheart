package id.co.blackheart.dto.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionSnapshot {

    private boolean hasOpenPosition;

    private String side;
    private String status;

    private BigDecimal entryPrice;
    private BigDecimal entryQty;
    private BigDecimal entryQuoteQty;

    private BigDecimal currentStopLossPrice;
    private BigDecimal initialStopLossPrice;
    private BigDecimal trailingStopPrice;
    private BigDecimal takeProfitPrice;

    private LocalDateTime entryTime;
}