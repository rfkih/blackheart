package id.co.blackheart.service.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Top-of-report summary — matches the "headline" table I produce in analyses.
 * {@code profitFactor} can legitimately be {@code null} when there are no
 * losses; the frontend renders that as "∞".
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Headline {
    private int tradeCount;
    private int wins;
    private int losses;
    private BigDecimal winRate;
    private BigDecimal profitFactor;
    private BigDecimal avgR;
    private BigDecimal avgWin;
    private BigDecimal avgLoss;
    private BigDecimal grossProfit;
    private BigDecimal grossLoss;
    private BigDecimal netPnl;
    private BigDecimal peakEquity;
    private BigDecimal maxDrawdown;
    private int maxConsecutiveLosses;
    private BigDecimal initialCapital;

    public static Headline zero() {
        Headline h = new Headline();
        h.setWinRate(BigDecimal.ZERO);
        h.setAvgR(BigDecimal.ZERO);
        h.setAvgWin(BigDecimal.ZERO);
        h.setAvgLoss(BigDecimal.ZERO);
        h.setGrossProfit(BigDecimal.ZERO);
        h.setGrossLoss(BigDecimal.ZERO);
        h.setNetPnl(BigDecimal.ZERO);
        h.setPeakEquity(BigDecimal.ZERO);
        h.setMaxDrawdown(BigDecimal.ZERO);
        return h;
    }
}
