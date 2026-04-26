package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class BacktestTradeDetailResponse {
    private UUID id;
    private UUID backtestRunId;
    /**
     * Owning strategy code (e.g. "LSR_V2", "VCB"). Surfaced so the trades
     * table can show "which strategy fired this trade" in multi-strategy runs.
     */
    private String strategyCode;
    /** Display name — falls back to {@code strategyCode} when not set. */
    private String strategyName;
    /**
     * The interval the strategy actually fired on. In multi-interval runs
     * this can differ across trades (e.g. LSR on 15m, VCB on 1h within
     * the same backtest).
     */
    private String interval;
    private String direction;
    private Long entryTime;
    private BigDecimal entryPrice;
    private Long exitTime;
    private BigDecimal exitPrice;
    private BigDecimal stopLossPrice;
    private BigDecimal tp1Price;
    private BigDecimal tp2Price;
    private BigDecimal quantity;
    private BigDecimal realizedPnl;
    private BigDecimal rMultiple;
    private List<BacktestPositionDetailResponse> positions;
}
