package id.co.blackheart.service.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Top-level diagnostics payload persisted on {@code backtest_run.analysis_snapshot}.
 *
 * <p>Intentionally flat JSON for easy consumption from the frontend card + my
 * message-based reviews. Field names mirror the manual analyses I've been
 * producing in chat so the shape is already familiar.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisReport {

    private UUID backtestRunId;
    private String strategyCode;
    private String strategyVersion;
    private String asset;
    private String interval;
    private int tradeCount;

    private Headline headline;
    private Map<String, List<BucketRow>> buckets;
    private MfeCapture mfeCapture;
    private List<TradeSnapshot> bestTrades;
    private List<TradeSnapshot> worstTrades;

    public static AnalysisReport empty(UUID runId) {
        AnalysisReport r = new AnalysisReport();
        r.setBacktestRunId(runId);
        r.setTradeCount(0);
        r.setHeadline(Headline.zero());
        r.setBuckets(Collections.emptyMap());
        return r;
    }
}
