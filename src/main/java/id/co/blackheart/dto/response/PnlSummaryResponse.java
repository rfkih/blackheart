package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PnlSummaryResponse {
    private String period;
    private BigDecimal realizedPnl;
    private BigDecimal unrealizedPnl;
    private BigDecimal totalPnl;
    private Integer tradeCount;
    private BigDecimal winRate;
    private Integer openCount;
    /** Mean per-trade return rate (pnl / notional × 100). Sizing-independent
     *  edge per trade — useful when {@code capital_allocation_pct} is small. */
    private BigDecimal avgTradeReturnPct;
    /** Compounded return assuming every trade had been sized at 90% of equity,
     *  in percent. Shows the "what if I sized aggressively" view of the same
     *  trade ledger. */
    private BigDecimal geometricReturnPctAtAlloc90;
}
