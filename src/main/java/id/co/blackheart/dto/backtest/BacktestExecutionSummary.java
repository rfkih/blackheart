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

    /** Mean of per-trade return rates (pnl / notional) in percent. Sizing-
     *  independent — answers "what's the average edge per trade". */
    private BigDecimal avgTradeReturnPct;

    /** Compounded equity multiplier minus 1, in percent, assuming every trade
     *  was sized at 90% of equity. Answers "what return would this strategy
     *  have produced if I'd bet near-full equity each time". See
     *  {@link id.co.blackheart.service.statistics.GeometricReturnCalculator}. */
    private BigDecimal geometricReturnPctAtAlloc90;
}
