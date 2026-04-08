package id.co.blackheart.dto.backtest;

import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.BacktestEquityPoint;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import id.co.blackheart.model.FeatureStore;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

@Data
@Builder
public class BacktestState {

    private BigDecimal cashBalance;

    /**
     * Synthetic equity tracking fields.
     */
    private BigDecimal currentEquity;
    private BigDecimal peakEquity;
    private BigDecimal maxDrawdownPercent;

    /**
     * Current active parent trade.
     * This still supports one parent trade at a time, matching your current design.
     */
    private BacktestTrade activeTrade;

    /**
     * Active child positions for the active parent trade.
     */
    private List<BacktestTradePosition> activeTradePositions;

    /**
     * Completed parent trades.
     */
    private List<BacktestTrade> completedTrades;

    /**
     * Completed child positions.
     */
    private List<BacktestTradePosition> completedTradePositions;

    private List<BacktestEquityPoint> equityPoints;

    /**
     * TreeMap index for O(log n) equity point upsert and previous-day lookup during backtest execution.
     * Converted to equityPoints list at the end of the backtest run.
     */
    @Builder.Default
    private TreeMap<LocalDate, BacktestEquityPoint> equityPointIndex = new TreeMap<>();

    /**
     * Deferred entry order — filled at the open price of the next bar to avoid look-ahead bias.
     */
    private PendingEntry pendingEntry;

    public record PendingEntry(StrategyDecision decision, String side, FeatureStore featureStore, FeatureStore biasFeatureStore) {}

    public static BacktestState initial(BacktestRun run) {
        return BacktestState.builder()
                .cashBalance(run.getInitialCapital())
                .currentEquity(run.getInitialCapital())
                .peakEquity(run.getInitialCapital())
                .maxDrawdownPercent(BigDecimal.ZERO)
                .activeTrade(null)
                .activeTradePositions(new ArrayList<>())
                .completedTrades(new ArrayList<>())
                .completedTradePositions(new ArrayList<>())
                .equityPoints(new ArrayList<>())
                .build();
    }
}