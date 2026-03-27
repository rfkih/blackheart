package id.co.blackheart.dto.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionSnapshot {

    private UUID tradeId;
    private UUID tradePositionId;

    private boolean hasOpenPosition;

    private String side;
    private String status;
    private String positionRole;

    private BigDecimal entryPrice;
    private BigDecimal entryQty;
    private BigDecimal entryQuoteQty;
    private BigDecimal remainingQty;

    private BigDecimal currentStopLossPrice;
    private BigDecimal initialStopLossPrice;
    private BigDecimal trailingStopPrice;
    private BigDecimal takeProfitPrice;

    private BigDecimal highestPriceSinceEntry;
    private BigDecimal lowestPriceSinceEntry;

    private LocalDateTime entryTime;
}