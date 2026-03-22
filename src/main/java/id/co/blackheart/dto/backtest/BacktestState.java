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
    private BigDecimal assetBalance;
    private BigDecimal peakEquity;
    private BigDecimal maxDrawdownPercent;

    /**
     * Active parent trade in memory.
     */
    private BacktestTrade activeTrade;

    /**
     * Active child positions in memory.
     */
    private List<BacktestTradePosition> activeTradePositions;

    /**
     * Completed parent trades in memory.
     */
    private List<BacktestTrade> completedTrades;

    /**
     * Completed child positions in memory.
     */
    private List<BacktestTradePosition> completedTradePositions;

    public static BacktestState initial(BacktestRun run) {
        return BacktestState.builder()
                .cashBalance(run.getInitialCapital())
                .assetBalance(BigDecimal.ZERO)
                .peakEquity(run.getInitialCapital())
                .maxDrawdownPercent(BigDecimal.ZERO)
                .activeTrade(null)
                .activeTradePositions(new ArrayList<>())
                .completedTrades(new ArrayList<>())
                .completedTradePositions(new ArrayList<>())
                .build();
    }
}