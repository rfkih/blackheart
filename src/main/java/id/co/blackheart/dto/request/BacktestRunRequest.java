package id.co.blackheart.dto.request;

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
public class BacktestRunRequest {

    private UUID userStrategyId;

    private String strategyName;
    private String asset;
    private String interval;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private BigDecimal initialCapital;
    private BigDecimal riskPerTradePct;
    private BigDecimal feeRate;
    private BigDecimal slippageRate;

    private BigDecimal minNotional;
    private BigDecimal minQty;
    private BigDecimal qtyStep;
}