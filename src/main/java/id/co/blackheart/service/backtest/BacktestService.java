package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    private final BacktestRunRepository backtestRunRepository;
    private final BacktestCoordinatorService backtestCoordinatorService;
    private final BacktestResponseMapper backtestMapperService;

    public BacktestRunResponse runBacktest(BacktestRunRequest request) {
        validateRequest(request);

        String resolvedStrategyCode = resolveStrategyCode(request);
        String resolvedStrategyName = request.getStrategyName() != null ? request.getStrategyName() : resolvedStrategyCode;

        BacktestRun backtestRun = BacktestRun.builder()
                .accountStrategyId(request.getAccountStrategyId())
                .strategyName(resolvedStrategyName)
                .strategyCode(resolvedStrategyCode)
                .asset(request.getAsset())
                .interval(request.getInterval())
                .status(STATUS_RUNNING)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .initialCapital(request.getInitialCapital())
                .riskPerTradePct(request.getRiskPerTradePct())
                .feePct(request.getFeeRate())
                .slippagePct(request.getSlippageRate())
                .minNotional(request.getMinNotional())
                .minQty(request.getMinQty())
                .qtyStep(request.getQtyStep())
                .allowLong(request.getAllowLong())
                .allowShort(request.getAllowShort())
                .maxOpenPositions(request.getMaxOpenPositions())
                .totalTrades(0)
                .totalWins(0)
                .totalLosses(0)
                .winRate(BigDecimal.ZERO)
                .grossProfit(BigDecimal.ZERO)
                .grossLoss(BigDecimal.ZERO)
                .netProfit(BigDecimal.ZERO)
                .maxDrawdownPct(BigDecimal.ZERO)
                .endingBalance(request.getInitialCapital())
                .build();

        backtestRun = backtestRunRepository.save(backtestRun);

        try {
            BacktestExecutionSummary summary = backtestCoordinatorService.execute(backtestRun);

            backtestRun.setStatus(STATUS_COMPLETED);
            backtestRun.setEndingBalance(summary.getFinalCapital());
            backtestRun.setTotalTrades(summary.getTotalTrades());
            backtestRun.setTotalWins(summary.getWinningTrades());
            backtestRun.setTotalLosses(summary.getLosingTrades());
            backtestRun.setWinRate(summary.getWinRate());
            backtestRun.setMaxDrawdownPct(summary.getMaxDrawdownPercent());
            backtestRun.setGrossProfit(summary.getGrossProfit());
            backtestRun.setGrossLoss(summary.getGrossLoss());
            backtestRun.setNetProfit(summary.getNetProfit());

            backtestRun = backtestRunRepository.save(backtestRun);

            return backtestMapperService.toRunResponse(backtestRun);

        } catch (Exception e) {
            log.error("Backtest failed | backtestRunId={}", backtestRun.getBacktestRunId(), e);

            backtestRun.setStatus(STATUS_FAILED);
            backtestRunRepository.save(backtestRun);

            throw e;
        }
    }

    /**
     * Resolves the strategy code(s) to store on BacktestRun.
     * Multi-strategy: codes joined as comma-separated string, e.g. "LSR_V2,VCB".
     * Single-strategy: the single code, falling back to strategyName.
     */
    private String resolveStrategyCode(BacktestRunRequest request) {
        List<String> codes = request.getStrategyCodes();
        if (codes != null && !codes.isEmpty()) {
            List<String> valid = codes.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .toList();
            if (!valid.isEmpty()) {
                return String.join(",", valid);
            }
        }
        if (request.getStrategyCode() != null && !request.getStrategyCode().isBlank()) {
            return request.getStrategyCode();
        }
        return request.getStrategyName();
    }

    private void validateRequest(BacktestRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("BacktestRunRequest cannot be null");
        }
        if (request.getAccountStrategyId() == null) {
            throw new IllegalArgumentException("accountStrategyId cannot be null");
        }

        boolean hasStrategyCodes = request.getStrategyCodes() != null
                && request.getStrategyCodes().stream().anyMatch(c -> c != null && !c.isBlank());
        boolean hasStrategyCode = request.getStrategyCode() != null && !request.getStrategyCode().isBlank();
        boolean hasStrategyName = request.getStrategyName() != null && !request.getStrategyName().isBlank();

        if (!hasStrategyCodes && !hasStrategyCode && !hasStrategyName) {
            throw new IllegalArgumentException("At least one of strategyCodes, strategyCode, or strategyName must be provided");
        }
        if (request.getAsset() == null || request.getAsset().isBlank()) {
            throw new IllegalArgumentException("asset cannot be blank");
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