package id.co.blackheart.dto.montecarlo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class MonteCarloPathSummary {

    private int pathIndex;
    private String label;            // BEST, MEDIAN, WORST

    private BigDecimal finalEquity;
    private BigDecimal totalReturnPct;
    private BigDecimal maxDrawdownPct;

    private boolean ruinBreached;
    private boolean drawdownThresholdBreached;

    /**
     * Full equity curve: equity[0] = initialCapital, equity[i] = equity after trade i.
     * Populated only for the three selected paths (BEST, MEDIAN, WORST).
     */
    private List<BigDecimal> equityCurve;
}