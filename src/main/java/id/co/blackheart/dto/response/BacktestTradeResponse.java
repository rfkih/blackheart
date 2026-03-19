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
public class BacktestTradeResponse {

    private UUID backtestTradeId;

    private UUID backtestRunId;

    private UUID userId;

    private String strategyName;

    private String interval;

    private String asset;

    private String side;

    private String status;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    private BigDecimal entryPrice;

    private BigDecimal exitPrice;

    private BigDecimal entryQty;

    private BigDecimal exitQty;

    private BigDecimal entryQuoteQty;

    private BigDecimal exitQuoteQty;

    private BigDecimal entryFee;

    private BigDecimal exitFee;

    private BigDecimal slippageAmount;

    private BigDecimal initialStopLossPrice;

    private BigDecimal currentStopLossPrice;

    private BigDecimal trailingStopPrice;

    private BigDecimal takeProfitPrice;

    private String exitReason;

    private String entryTrendRegime;

    private BigDecimal entryAdx;

    private BigDecimal entryAtr;

    private BigDecimal entryRsi;

    private BigDecimal plPercent;

    private BigDecimal plAmount;

    private LocalDateTime createdAt;
}