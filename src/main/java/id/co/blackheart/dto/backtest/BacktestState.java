package id.co.blackheart.dto.backtest;

import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
                .build();
    }
}