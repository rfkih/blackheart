package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class StrategyPnlResponse {
    private String strategyCode;
    private BigDecimal realizedPnl;
    private Integer tradeCount;
    private BigDecimal winRate;
    /** Mean per-trade return rate (pnl / notional × 100). Sizing-independent. */
    private BigDecimal avgTradeReturnPct;
    /** Compounded return assuming 90% of equity sized per trade, in percent. */
    private BigDecimal geometricReturnPctAtAlloc90;
}
