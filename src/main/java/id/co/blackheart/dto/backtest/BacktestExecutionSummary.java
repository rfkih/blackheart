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

    private BigDecimal netProfit;
    private BigDecimal grossProfit;
    private BigDecimal grossLoss;
    private BigDecimal expectancy;
    private BigDecimal avgWin;
    private BigDecimal avgLoss;
    private BigDecimal maxDrawdownAmount;
    private BigDecimal sortinoRatio;
    /** Probabilistic Sharpe Ratio in [0, 1] — null when sample is too small. */
    private BigDecimal psr;
}
