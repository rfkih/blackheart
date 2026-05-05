package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Minimal feature-snapshot shown in "Best 5 / Worst 5" sections — enough for
 * me to pattern-match why a given trade won or lost without pulling the full
 * trade row.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TradeSnapshot {
    private UUID tradeId;
    private String entryTime;
    private String side;
    private BigDecimal pnl;
    private BigDecimal r;
    private BigDecimal entryAdx;
    private BigDecimal entryRsi;
    private BigDecimal entryClv;
    private BigDecimal entryRvol;
    private BigDecimal biasAdx;
    private BigDecimal mfeR;
    private BigDecimal maeR;
    private String exitReason;
}
