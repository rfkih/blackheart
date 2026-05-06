package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestEquityPoint;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.service.statistics.SharpeStatistics;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

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

        BigDecimal avgWin = winningTrades == 0
                ? BigDecimal.ZERO
                : grossProfit.divide(BigDecimal.valueOf(winningTrades), 6, RoundingMode.HALF_UP);

        BigDecimal avgLoss = losingTrades == 0
                ? BigDecimal.ZERO
                : grossLoss.divide(BigDecimal.valueOf(losingTrades), 6, RoundingMode.HALF_UP);

        // Expectancy in USDT per trade. Uses signed avgLoss so a positive
        // expectancy is "wins more than cover losses", matching how PnL is
        // computed elsewhere — grossLoss is already an absolute value, so we
        // subtract the loss-side weight rather than add it.
        BigDecimal expectancy = totalTrades == 0
                ? BigDecimal.ZERO
                : netProfit.divide(BigDecimal.valueOf(totalTrades), 6, RoundingMode.HALF_UP);

        BigDecimal maxDrawdownAmount = calculateMaxDrawdownAmount(state);

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
                .maxDrawdownAmount(maxDrawdownAmount)
                .totalReturnPercent(totalReturnPercent)
                .sharpeRatio(calculateSharpeRatio(state))
                .sortinoRatio(calculateSortinoRatio(state))
                .psr(calculatePsr(state))
                .avgWin(avgWin)
                .avgLoss(avgLoss)
                .expectancy(expectancy)
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

    /**
     * Sortino mirrors Sharpe but penalises downside-only volatility — std dev
     * is computed over negative daily returns only. If no losing day occurred
     * we return ZERO (rather than infinity) to keep the field render-safe;
     * a strategy with zero losing days for the cohort is more meaningfully
     * judged by other metrics anyway.
     */
    private BigDecimal calculateSortinoRatio(BacktestState state) {
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

        BigDecimal mean = dailyReturns.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), 10, RoundingMode.HALF_UP);

        // Downside deviation: square only the negative returns, average over
        // the full sample size (standard Sortino convention).
        BigDecimal downsideSumSq = dailyReturns.stream()
                .filter(r -> r.compareTo(BigDecimal.ZERO) < 0)
                .map(r -> r.pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (downsideSumSq.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal downsideVariance = downsideSumSq.divide(
                BigDecimal.valueOf(dailyReturns.size()), 10, RoundingMode.HALF_UP);
        BigDecimal downsideDev = BigDecimal.valueOf(Math.sqrt(downsideVariance.doubleValue()));
        BigDecimal annualizationFactor = BigDecimal.valueOf(Math.sqrt(252));

        return mean.divide(downsideDev, 10, RoundingMode.HALF_UP)
                .multiply(annualizationFactor)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Probabilistic Sharpe Ratio — P(true Sharpe > 0) given the observed
     * per-period Sharpe, sample size, and the moments of the return series.
     * Computed on the same daily return series Sharpe uses, but with the
     * <i>per-period</i> Sharpe (no √252 annualization) since PSR's
     * statistical machinery operates on raw periods.
     *
     * <p>Returns {@code null} when the sample is too small to be meaningful
     * (&lt; 10 daily returns) — better to render "—" than an unreliable
     * 0.50-ish value that suggests certainty we don't have.
     */
    private BigDecimal calculatePsr(BacktestState state) {
        if (state.getEquityPoints() == null || state.getEquityPoints().size() < 10) {
            return null;
        }

        double[] returns = state.getEquityPoints().stream()
                .map(BacktestEquityPoint::getDailyReturnPct)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .toArray();
        if (returns.length < 10) return null;

        double mean = SharpeStatistics.mean(returns);
        double sd = SharpeStatistics.stddev(returns);
        if (sd <= 0.0) return null;

        double srPerPeriod = mean / sd;
        double skew = SharpeStatistics.skewness(returns);
        double kurt = SharpeStatistics.kurtosis(returns);

        double psr = SharpeStatistics.psr(srPerPeriod, 0.0, returns.length, skew, kurt);
        if (Double.isNaN(psr) || Double.isInfinite(psr)) return null;

        return BigDecimal.valueOf(psr).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Max drawdown in absolute USDT — peak total-equity minus the lowest
     * total-equity reached after that peak. Walks the equity-point series so
     * we don't need a parallel field on {@code BacktestState}.
     */
    private BigDecimal calculateMaxDrawdownAmount(BacktestState state) {
        if (CollectionUtils.isEmpty(state.getEquityPoints())) {
            return BigDecimal.ZERO;
        }
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (BacktestEquityPoint p : state.getEquityPoints()) {
            BigDecimal eq = p.getTotalEquity();
            if (eq == null) continue;
            if (eq.compareTo(peak) > 0) peak = eq;
            BigDecimal dd = peak.subtract(eq);
            if (dd.compareTo(maxDd) > 0) maxDd = dd;
        }
        return maxDd.setScale(8, RoundingMode.HALF_UP);
    }
}