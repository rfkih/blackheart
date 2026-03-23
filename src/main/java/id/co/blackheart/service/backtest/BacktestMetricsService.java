package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class BacktestMetricsService {

    public BacktestExecutionSummary buildSummary(BacktestRun run, BacktestState state) {
        List<BacktestTrade> trades = state.getCompletedTrades();

        int totalTrades = trades.size();

        int winningTrades = (int) trades.stream()
                .filter(t -> t.getRealizedPnlAmount() != null
                        && t.getRealizedPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();

        int losingTrades = (int) trades.stream()
                .filter(t -> t.getRealizedPnlAmount() != null
                        && t.getRealizedPnlAmount().compareTo(BigDecimal.ZERO) < 0)
                .count();

        BigDecimal grossProfit = trades.stream()
                .map(BacktestTrade::getRealizedPnlAmount)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLoss = trades.stream()
                .map(BacktestTrade::getRealizedPnlAmount)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal winRate = totalTrades == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(winningTrades)
                .divide(BigDecimal.valueOf(totalTrades), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        BigDecimal profitFactor = grossLoss.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : grossProfit.divide(grossLoss, 6, RoundingMode.HALF_UP);

        BigDecimal finalCapital = state.getCashBalance().setScale(3, RoundingMode.HALF_UP);

        BigDecimal totalReturnPercent = finalCapital.subtract(run.getInitialCapital())
                .divide(run.getInitialCapital(), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        return BacktestExecutionSummary.builder()
                .finalCapital(finalCapital)
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(winRate)
                .profitFactor(profitFactor)
                .maxDrawdownPercent(state.getMaxDrawdownPercent())
                .totalReturnPercent(totalReturnPercent)
                .sharpeRatio(BigDecimal.ZERO)
                .build();
    }
}