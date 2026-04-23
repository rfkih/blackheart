package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    /** Binance USD-M futures default taker fee (0.04%). Used when the request omits feeRate. */
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0004");
    /** Conservative slippage default (0.05%) — applied when the request omits slippageRate. */
    private static final BigDecimal DEFAULT_SLIPPAGE_RATE = new BigDecimal("0.0005");

    private final BacktestRunRepository backtestRunRepository;
    private final BacktestCoordinatorService backtestCoordinatorService;
    private final BacktestResponseMapper backtestMapperService;
    private final AccountStrategyOwnershipGuard ownershipGuard;

    public BacktestRunResponse runBacktest(UUID userId, BacktestRunRequest request) {
        validateRequest(request);

        // Verify the caller owns every account strategy id they are about to use
        // for params resolution. Without this, a user could run a backtest
        // "from" another tenant's tuned params — information disclosure.
        ownershipGuard.assertOwned(userId, request.getAccountStrategyId());
        if (request.getStrategyAccountStrategyIds() != null) {
            for (UUID perStrategyId : request.getStrategyAccountStrategyIds().values()) {
                if (perStrategyId != null) {
                    ownershipGuard.assertOwned(userId, perStrategyId);
                }
            }
        }

        String resolvedStrategyCode = resolveStrategyCode(request);
        String resolvedStrategyName = request.getStrategyName() != null ? request.getStrategyName() : resolvedStrategyCode;
        BigDecimal resolvedFee = request.getFeeRate() != null ? request.getFeeRate() : DEFAULT_FEE_RATE;
        BigDecimal resolvedSlippage = request.getSlippageRate() != null
                ? request.getSlippageRate()
                : DEFAULT_SLIPPAGE_RATE;

        Map<String, Map<String, Object>> overrides = request.getStrategyParamOverrides();
        if (overrides != null && !overrides.isEmpty()) {
            log.info("Backtest submitted with param overrides across {} strateg(ies): {}",
                    overrides.size(), overrides.keySet());
            // TODO(slice-6): plumb overrides into BacktestCoordinatorService so per-strategy
            //                LsrParams.merge() / VcbParams.merge() picks them up at resolve time.
        }

        BacktestRun backtestRun = BacktestRun.builder()
                .userId(userId)
                .accountStrategyId(request.getAccountStrategyId())
                .strategyAccountStrategyIds(request.getStrategyAccountStrategyIds())
                .strategyName(resolvedStrategyName)
                .strategyCode(resolvedStrategyCode)
                .asset(request.getAsset())
                .interval(request.getInterval())
                .status(STATUS_RUNNING)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .initialCapital(request.getInitialCapital())
                .riskPerTradePct(request.getRiskPerTradePct())
                .feePct(resolvedFee)
                .slippagePct(resolvedSlippage)
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

        boolean hasStrategyCodes = request.getStrategyCodes() != null
                && request.getStrategyCodes().stream().anyMatch(c -> c != null && !c.isBlank());
        boolean hasStrategyCode = request.getStrategyCode() != null && !request.getStrategyCode().isBlank();
        boolean hasStrategyName = request.getStrategyName() != null && !request.getStrategyName().isBlank();

        if (!hasStrategyCodes && !hasStrategyCode && !hasStrategyName) {
            throw new IllegalArgumentException("At least one of strategyCodes, strategyCode, or strategyName must be provided");
        }
        if (request.getAccountStrategyId() == null) {
            throw new IllegalArgumentException("accountStrategyId is required (backtest_run.account_strategy_id is NOT NULL)");
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

        // Cap backtest range at 5 years of 1m candles (~2.6M bars) to prevent
        // a single request from pegging CPU + pulling millions of rows out of
        // Postgres. Longer ranges on coarser intervals (e.g. 1h) pass through.
        Duration span = Duration.between(request.getStartTime(), request.getEndTime());
        if (span.toDays() > 365L * 5) {
            throw new IllegalArgumentException(
                    "Backtest range exceeds the 5-year limit. Split the run into smaller windows.");
        }
        // feeRate and slippageRate are optional — defaults kick in at build time.
    }
}
