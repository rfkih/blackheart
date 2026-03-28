package id.co.blackheart.dto.response;

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
public class BacktestRunResponse {

    private UUID backtestRunId;

    private UUID accountStrategyId;

    private String strategyName;

    private String asset;

    private String interval;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal initialCapital;

    private BigDecimal endingBalance;

    private BigDecimal riskPerTradePct;

    private BigDecimal feePct;

    private BigDecimal slippagePct;

    private BigDecimal minNotional;

    private BigDecimal minQty;

    private BigDecimal qtyStep;

    private Integer totalTrades;

    private Integer totalWins;

    private Integer totalLosses;

    private BigDecimal winRate;

    private BigDecimal grossProfit;

    private BigDecimal grossLoss;

    private BigDecimal netProfit;

    private BigDecimal maxDrawdownPct;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}