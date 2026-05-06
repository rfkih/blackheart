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
import org.springframework.util.CollectionUtils;

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

        if (state != null && !CollectionUtils.isEmpty(state.getEquityPoints())) {
            backtestEquityPointRepository.saveAllAndFlush(state.getEquityPoints());
        }

        if (!persistTradeDetails) {
            return;
        }

        String actor = resolveActor(backtestRun);

        if (state != null && !CollectionUtils.isEmpty(state.getCompletedTrades())) {
            state.getCompletedTrades().forEach(t -> stampActor(t::getCreatedBy, t::setCreatedBy, t::setUpdatedBy, actor));
            backtestTradeRepository.saveAllAndFlush(state.getCompletedTrades());
        }

        if (state != null && !CollectionUtils.isEmpty(state.getCompletedTradePositions())) {
            state.getCompletedTradePositions().forEach(p -> stampActor(p::getCreatedBy, p::setCreatedBy, p::setUpdatedBy, actor));
            backtestTradePositionRepository.saveAllAndFlush(state.getCompletedTradePositions());
        }
    }

    /**
     * Resolves the audit actor stamped on persisted backtest trades and
     * positions. Uses the run's owning user when available so the audit
     * trail attributes rows to the trader who triggered the backtest;
     * falls back to a {@code "BACKTEST"} sentinel when the run has no
     * userId (synthetic / system-triggered runs).
     */
    private String resolveActor(BacktestRun backtestRun) {
        if (backtestRun != null && backtestRun.getUserId() != null) {
            return "BACKTEST:" + backtestRun.getUserId();
        }
        return "BACKTEST";
    }

    /**
     * Stamps {@code createdBy} (only when null — preserves prior value on
     * re-persist) and {@code updatedBy} (always overwrites) on a backtest
     * audit-tracked entity. Hibernate fills {@code created_time} /
     * {@code updated_time} automatically; the {@code *_by} fields require
     * explicit service-layer population per BaseEntity's contract.
     */
    private void stampActor(
            java.util.function.Supplier<String> getCreatedBy,
            java.util.function.Consumer<String> setCreatedBy,
            java.util.function.Consumer<String> setUpdatedBy,
            String actor
    ) {
        if (getCreatedBy.get() == null) setCreatedBy.accept(actor);
        setUpdatedBy.accept(actor);
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