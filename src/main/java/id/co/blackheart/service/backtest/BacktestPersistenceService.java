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

import java.util.function.Consumer;
import java.util.function.Supplier;

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
            Supplier<String> getCreatedBy,
            Consumer<String> setCreatedBy,
            Consumer<String> setUpdatedBy,
            String actor
    ) {
        if (getCreatedBy.get() == null) setCreatedBy.accept(actor);
        setUpdatedBy.accept(actor);
    }

    private void applySummary(BacktestRun backtestRun, BacktestExecutionSummary summary) {
        backtestRun.setStatus("COMPLETED");

        copyIfPresent(summary::getFinalCapital, backtestRun::setEndingBalance);
        copyIfPresent(summary::getGrossProfit, backtestRun::setGrossProfit);
        copyIfPresent(summary::getGrossLoss, backtestRun::setGrossLoss);

        if (summary.getNetProfit() != null) {
            backtestRun.setNetProfit(summary.getNetProfit());
        } else if (summary.getFinalCapital() != null && backtestRun.getInitialCapital() != null) {
            backtestRun.setNetProfit(summary.getFinalCapital().subtract(backtestRun.getInitialCapital()));
        }

        copyIfPresent(summary::getTotalTrades, backtestRun::setTotalTrades);
        copyIfPresent(summary::getWinningTrades, backtestRun::setTotalWins);
        copyIfPresent(summary::getLosingTrades, backtestRun::setTotalLosses);
        copyIfPresent(summary::getWinRate, backtestRun::setWinRate);
        copyIfPresent(summary::getProfitFactor, backtestRun::setProfitFactor);
        copyIfPresent(summary::getMaxDrawdownPercent, backtestRun::setMaxDrawdownPct);
        copyIfPresent(summary::getTotalReturnPercent, backtestRun::setReturnPct);
        copyIfPresent(summary::getSharpeRatio, backtestRun::setSharpeRatio);
        copyIfPresent(summary::getSortinoRatio, backtestRun::setSortinoRatio);
        copyIfPresent(summary::getPsr, backtestRun::setPsr);
        copyIfPresent(summary::getAvgWin, backtestRun::setAvgWin);
        copyIfPresent(summary::getAvgLoss, backtestRun::setAvgLoss);
        copyIfPresent(summary::getMaxDrawdownAmount, backtestRun::setMaxDrawdownAmount);
        copyIfPresent(summary::getExpectancy, backtestRun::setExpectancy);
    }

    private static <T> void copyIfPresent(Supplier<T> getter, Consumer<T> setter) {
        T value = getter.get();
        if (value != null) {
            setter.accept(value);
        }
    }
}