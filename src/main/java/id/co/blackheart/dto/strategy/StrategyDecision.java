package id.co.blackheart.dto.strategy;

import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class StrategyDecision {

    private DecisionType decisionType;

    private String strategyName;
    private String strategyInterval;
    private String side;

    private String exitReason;
    private String reason;

    private UUID tradeId;
    private UUID tradePositionId;

    private BigDecimal positionSize;
    private BigDecimal stopLossPrice;
    private BigDecimal takeProfitPrice;
    private BigDecimal trailingStopPrice;

    private BigDecimal entryAdx;
    private BigDecimal entryAtr;
    private BigDecimal entryRsi;
    private String entryTrendRegime;
}