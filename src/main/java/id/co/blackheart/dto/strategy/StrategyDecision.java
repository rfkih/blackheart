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

    /**
     * Initial or updated protection levels.
     */
    private BigDecimal stopLossPrice;
    private BigDecimal trailingStopPrice;

    /**
     * Strategy-owned target prices.
     */
    private BigDecimal takeProfitPrice1;
    private BigDecimal takeProfitPrice2;
    private BigDecimal takeProfitPrice3;

    /**
     * Strategy-owned exit structure:
     * SINGLE, TP1_RUNNER, TP1_TP2_RUNNER, RUNNER_ONLY
     */
    private String exitStructure;

    /**
     * Which child role should be updated:
     * ALL, SINGLE, TP1, TP2, TP3, RUNNER
     */
    private String targetPositionRole;

    private BigDecimal entryAdx;
    private BigDecimal entryAtr;
    private BigDecimal entryRsi;
    private String entryTrendRegime;
}