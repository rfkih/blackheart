package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestEquityPoint;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

@Service
public class BacktestMetricsService {

    public BacktestExecutionSummary buildSummary(BacktestRun run, BacktestState state) {
        List<BacktestTrade> trades = state.getCompletedTrades();

        int totalTrades = trades.size();

        // Win = trade closed with positive net P&L (fee-adjusted); consistent with grossProfit basis.
        int winningTrades = (int) trades.stream()
                .filter(this::isWin)
                .count();

        int losingTrades = (int) trades.stream()
                .filter(this::isLoss)
                .count();

        // Gross profit = total accumulated profit across all winning trades (net of fees)
        BigDecimal grossProfit = trades.stream()
                .map(BacktestTrade::getRealizedPnlAmount)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Gross loss = total accumulated loss across all losing trades (net of fees, absolute value)
        BigDecimal grossLoss = trades.stream()
                .map(BacktestTrade::getRealizedPnlAmount)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Net profit = gross profit - gross loss
        BigDecimal netProfit = grossProfit.subtract(grossLoss);

        BigDecimal winRate = totalTrades == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(winningTrades)
                .divide(BigDecimal.valueOf(totalTrades), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        BigDecimal profitFactor;
        if (grossLoss.compareTo(BigDecimal.ZERO) == 0) {
            // No losing trades — profit factor is infinite; use 9999 as sentinel
            profitFactor = grossProfit.compareTo(BigDecimal.ZERO) > 0
                    ? new BigDecimal("9999.000000")
                    : BigDecimal.ZERO;
        } else {
            profitFactor = grossProfit.divide(grossLoss, 6, RoundingMode.HALF_UP);
        }

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
                .grossProfit(grossProfit)
                .grossLoss(grossLoss)
                .netProfit(netProfit)
                .profitFactor(profitFactor)
                .maxDrawdownPercent(state.getMaxDrawdownPercent())
                .totalReturnPercent(totalReturnPercent)
                .sharpeRatio(calculateSharpeRatio(state))
                .build();
    }

    /**
     * A trade is a win if it closed with positive net P&L (after both entry and exit fees).
     * This is consistent with how grossProfit is accumulated and aligns with
     * real-world definition: a TP exit that is eaten by fees is not a win.
     */
    private boolean isWin(BacktestTrade trade) {
        return trade.getRealizedPnlAmount() != null
                && trade.getRealizedPnlAmount().compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isLoss(BacktestTrade trade) {
        return trade.getRealizedPnlAmount() != null
                && trade.getRealizedPnlAmount().compareTo(BigDecimal.ZERO) < 0;
    }

    private BigDecimal calculateSharpeRatio(BacktestState state) {
        if (state.getEquityPoints() == null || state.getEquityPoints().size() < 2) {
            return BigDecimal.ZERO;
        }

        List<BigDecimal> dailyReturns = state.getEquityPoints().stream()
                .map(BacktestEquityPoint::getDailyReturnPct)
                .filter(Objects::nonNull)
                .toList();

        if (dailyReturns.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal n = BigDecimal.valueOf(dailyReturns.size());
        BigDecimal mean = dailyReturns.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(n, 10, RoundingMode.HALF_UP);

        BigDecimal variance = dailyReturns.stream()
                .map(r -> r.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size() - 1), 10, RoundingMode.HALF_UP);

        if (variance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal annualizationFactor = BigDecimal.valueOf(Math.sqrt(252));

        return mean.divide(stdDev, 10, RoundingMode.HALF_UP)
                .multiply(annualizationFactor)
                .setScale(4, RoundingMode.HALF_UP);
    }
}