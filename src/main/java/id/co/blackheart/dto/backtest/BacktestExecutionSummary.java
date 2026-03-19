package id.co.blackheart.dto.backtest;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BacktestExecutionSummary {
    private BigDecimal finalCapital;
    private Integer totalTrades;
    private Integer winningTrades;
    private Integer losingTrades;
    private BigDecimal winRate;
    private BigDecimal profitFactor;
    private BigDecimal maxDrawdownPercent;
    private BigDecimal totalReturnPercent;
    private BigDecimal sharpeRatio;
}
