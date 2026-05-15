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
    /** V60 — mean per-trade return rate (pnl / notional × 100). Sizing-
     *  independent companion to {@link #totalReturnPct}. */
    private BigDecimal avgTradeReturnPct;
    /** V60 — compounded return assuming 90% of equity sized per trade, in
     *  percent. Clamps to ruin (multiplier 0) on any -100%+ step. */
    private BigDecimal geometricReturnPctAtAlloc90;
}
