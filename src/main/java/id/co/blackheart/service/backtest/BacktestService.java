package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    private final BacktestRunRepository backtestRunRepository;
    private final BacktestCoordinatorService backtestCoordinatorService;
    private final BacktestMapperService backtestMapperService;

    public BacktestRunResponse runBacktest(BacktestRunRequest request) {
        validateRequest(request);

        BacktestRun backtestRun = BacktestRun.builder()
                .userId(request.getUserId())
                .runName(request.getRunName())
                .strategyName(request.getStrategyName())
                .symbol(request.getSymbol())
                .interval(request.getInterval())
                .status(STATUS_RUNNING)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .initialCapital(request.getInitialCapital())
                .feeRate(request.getFeeRate())
                .slippageRate(request.getSlippageRate())
                .allowLong(Boolean.TRUE.equals(request.getAllowLong()))
                .allowShort(Boolean.TRUE.equals(request.getAllowShort()))
                .maxOpenPositions(request.getMaxOpenPositions() == null ? 1 : request.getMaxOpenPositions())
                .createdAt(LocalDateTime.now())
                .build();

        backtestRun = backtestRunRepository.save(backtestRun);

        try {
            BacktestExecutionSummary summary = backtestCoordinatorService.execute(backtestRun);

            backtestRun.setStatus(STATUS_COMPLETED);
            backtestRun.setFinalCapital(summary.getFinalCapital());
            backtestRun.setTotalTrades(summary.getTotalTrades());
            backtestRun.setWinningTrades(summary.getWinningTrades());
            backtestRun.setLosingTrades(summary.getLosingTrades());
            backtestRun.setWinRate(summary.getWinRate());
            backtestRun.setProfitFactor(summary.getProfitFactor());
            backtestRun.setMaxDrawdownPercent(summary.getMaxDrawdownPercent());
            backtestRun.setTotalReturnPercent(summary.getTotalReturnPercent());
            backtestRun.setSharpeRatio(summary.getSharpeRatio());
            backtestRun.setUpdatedAt(LocalDateTime.now());

            backtestRun = backtestRunRepository.save(backtestRun);

            return backtestMapperService.toRunResponse(backtestRun);

        } catch (Exception e) {
            log.error("Backtest failed | backtestRunId={}", backtestRun.getBacktestRunId(), e);

            backtestRun.setStatus(STATUS_FAILED);
            backtestRun.setUpdatedAt(LocalDateTime.now());
            backtestRunRepository.save(backtestRun);

            throw e;
        }
    }

    private void validateRequest(BacktestRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("BacktestRunRequest cannot be null");
        }
        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (request.getStrategyName() == null || request.getStrategyName().isBlank()) {
            throw new IllegalArgumentException("strategyName cannot be blank");
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (request.getInterval() == null || request.getInterval().isBlank()) {
            throw new IllegalArgumentException("interval cannot be blank");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new IllegalArgumentException("startTime and endTime cannot be null");
        }
        if (request.getStartTime().isAfter(request.getEndTime())) {
            throw new IllegalArgumentException("startTime cannot be after endTime");
        }
        if (request.getInitialCapital() == null || request.getInitialCapital().signum() <= 0) {
            throw new IllegalArgumentException("initialCapital must be greater than zero");
        }
        if (request.getFeeRate() == null) {
            throw new IllegalArgumentException("feeRate cannot be null");
        }
        if (request.getSlippageRate() == null) {
            throw new IllegalArgumentException("slippageRate cannot be null");
        }
    }
}