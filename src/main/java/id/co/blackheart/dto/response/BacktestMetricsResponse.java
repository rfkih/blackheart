package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BacktestMetricsResponse {
    private BigDecimal totalReturn;
    private BigDecimal totalReturnPct;
    private BigDecimal winRate;
    private BigDecimal profitFactor;
    private BigDecimal avgWinUsdt;
    private BigDecimal avgLossUsdt;
    private BigDecimal maxDrawdown;
    private BigDecimal maxDrawdownPct;
    private BigDecimal sharpe;
    private BigDecimal sortino;
    /** Probabilistic Sharpe Ratio — null when sample too small. */
    private BigDecimal psr;
    private Integer totalTrades;
    private Integer winningTrades;
    private Integer losingTrades;
}
