package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestEquityPointRepository;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.repository.BacktestTradePositionRepository;
import id.co.blackheart.repository.BacktestTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BacktestPersistenceService {

    private final BacktestRunRepository backtestRunRepository;
    private final BacktestTradeRepository backtestTradeRepository;
    private final BacktestTradePositionRepository backtestTradePositionRepository;
    private final BacktestEquityPointRepository backtestEquityPointRepository;

    @Transactional
    public void persist(
            BacktestRun backtestRun,
            BacktestState state,
            BacktestExecutionSummary summary,
            boolean persistTradeDetails
    ) {
        if (backtestRun == null) {
            throw new IllegalArgumentException("BacktestRun must not be null");
        }

        if (summary == null) {
            throw new IllegalArgumentException("BacktestExecutionSummary must not be null");
        }

        applySummary(backtestRun, summary);
        backtestRunRepository.saveAndFlush(backtestRun);

        if (state != null && state.getEquityPoints() != null && !state.getEquityPoints().isEmpty()) {
            backtestEquityPointRepository.saveAllAndFlush(state.getEquityPoints());
        }

        if (!persistTradeDetails) {
            return;
        }

        if (state != null && state.getCompletedTrades() != null && !state.getCompletedTrades().isEmpty()) {
            backtestTradeRepository.saveAllAndFlush(state.getCompletedTrades());
        }

        if (state != null && state.getCompletedTradePositions() != null && !state.getCompletedTradePositions().isEmpty()) {
            backtestTradePositionRepository.saveAllAndFlush(state.getCompletedTradePositions());
        }
    }

    private void applySummary(BacktestRun backtestRun, BacktestExecutionSummary summary) {
        backtestRun.setStatus("COMPLETED");

        if (summary.getFinalCapital() != null) {
            backtestRun.setEndingBalance(summary.getFinalCapital());
        }

        if (summary.getGrossProfit() != null) {
            backtestRun.setGrossProfit(summary.getGrossProfit());
        }

        if (summary.getGrossLoss() != null) {
            backtestRun.setGrossLoss(summary.getGrossLoss());
        }

        if (summary.getNetProfit() != null) {
            backtestRun.setNetProfit(summary.getNetProfit());
        } else if (summary.getFinalCapital() != null && backtestRun.getInitialCapital() != null) {
            backtestRun.setNetProfit(summary.getFinalCapital().subtract(backtestRun.getInitialCapital()));
        }

        if (summary.getTotalTrades() != null) {
            backtestRun.setTotalTrades(summary.getTotalTrades());
        }

        if (summary.getWinningTrades() != null) {
            backtestRun.setTotalWins(summary.getWinningTrades());
        }

        if (summary.getLosingTrades() != null) {
            backtestRun.setTotalLosses(summary.getLosingTrades());
        }

        if (summary.getWinRate() != null) {
            backtestRun.setWinRate(summary.getWinRate());
        }

        if (summary.getProfitFactor() != null) {
            backtestRun.setProfitFactor(summary.getProfitFactor());
        }

        if (summary.getMaxDrawdownPercent() != null) {
            backtestRun.setMaxDrawdownPct(summary.getMaxDrawdownPercent());
        }

        if (summary.getTotalReturnPercent() != null) {
            backtestRun.setReturnPct(summary.getTotalReturnPercent());
        }

        if (summary.getSharpeRatio() != null) {
            backtestRun.setSharpeRatio(summary.getSharpeRatio());
        }

        if (summary.getSortinoRatio() != null) {
            backtestRun.setSortinoRatio(summary.getSortinoRatio());
        }

        if (summary.getPsr() != null) {
            backtestRun.setPsr(summary.getPsr());
        }

        if (summary.getAvgWin() != null) {
            backtestRun.setAvgWin(summary.getAvgWin());
        }

        if (summary.getAvgLoss() != null) {
            backtestRun.setAvgLoss(summary.getAvgLoss());
        }

        if (summary.getMaxDrawdownAmount() != null) {
            backtestRun.setMaxDrawdownAmount(summary.getMaxDrawdownAmount());
        }

        if (summary.getExpectancy() != null) {
            backtestRun.setExpectancy(summary.getExpectancy());
        }
    }
}