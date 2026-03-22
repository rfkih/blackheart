package id.co.blackheart.dto.backtest;

import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
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
     * Active parent trade only.
     * Child split positions are stored in backtest_trade_position table.
     */
    private BacktestTrade activeTrade;

    private List<BacktestTrade> completedTrades;

    public static BacktestState initial(BacktestRun run) {
        return BacktestState.builder()
                .cashBalance(run.getInitialCapital())
                .assetBalance(BigDecimal.ZERO)
                .peakEquity(run.getInitialCapital())
                .maxDrawdownPercent(BigDecimal.ZERO)
                .activeTrade(null)
                .completedTrades(new ArrayList<>())
                .build();
    }
}