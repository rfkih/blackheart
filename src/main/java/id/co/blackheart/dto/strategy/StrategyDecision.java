package id.co.blackheart.dto.strategy;

import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class StrategyDecision {
    private DecisionType decisionType;
    private String strategyName;
    private String strategyInterval;
    private String side;
    private String exitReason;
    private String reason;

    private BigDecimal positionSize;
    private BigDecimal stopLossPrice;
    private BigDecimal takeProfitPrice;
    private BigDecimal trailingStopPrice;

    private BigDecimal entryAdx;
    private BigDecimal entryAtr;
    private BigDecimal entryRsi;
    private String entryTrendRegime;
}