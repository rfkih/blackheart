package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One row of a feature-bucket breakdown, e.g. "ADX [25,30) n=9 WR=11% PnL=-6.23". */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BucketRow {
    private double low;
    private double high;
    private int count;
    private int wins;
    private BigDecimal winRate;
    private BigDecimal totalPnl;
}
