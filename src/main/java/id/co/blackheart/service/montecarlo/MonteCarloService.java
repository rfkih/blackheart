package id.co.blackheart.service.montecarlo;

import id.co.blackheart.dto.montecarlo.MonteCarloResponse;
import id.co.blackheart.dto.request.MonteCarloRequest;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.MonteCarloRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.repository.BacktestTradeRepository;
import id.co.blackheart.repository.MonteCarloRunRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonteCarloService {

    private static final int MIN_TRADES    = 2;
    private static final int MAX_SIMULATIONS = 100_000;

    private final BacktestRunRepository backtestRunRepository;
    private final BacktestTradeRepository backtestTradeRepository;
    private final MonteCarloRunRepository monteCarloRunRepository;
    private final MonteCarloEngine monteCarloEngine;

    public MonteCarloResponse run(UUID userId, MonteCarloRequest request) {
        validateRequest(request);

        // Ownership check: only the owner of the backtest run may simulate from it.
        // Treat "not found" and "not yours" identically.
        BacktestRun backtestRun = backtestRunRepository
                .findByIdAndUserId(request.getBacktestRunId(), userId)
                .orElseThrow(() -> new EntityNotFoundException("Not found"));

        if (!"COMPLETED".equalsIgnoreCase(backtestRun.getStatus())) {
            throw new IllegalStateException(
                    "BacktestRun must be COMPLETED. Current status: " + backtestRun.getStatus());
        }

        List<BacktestTrade> trades = backtestTradeRepository.findByBacktestRunIdAndStatus(request.getBacktestRunId(), "CLOSED");

        if (ObjectUtils.isEmpty(trades) || trades.size() < MIN_TRADES) {
            throw new IllegalStateException(
                    "At least " + MIN_TRADES + " closed trades are required. Found: "
                    + (ObjectUtils.isEmpty(trades) ? 0 : trades.size()));
        }

        BigDecimal initialCapital = ObjectUtils.isNotEmpty(request.getInitialCapital())
                ? request.getInitialCapital()
                : backtestRun.getInitialCapital();

        long effectiveSeed = ObjectUtils.isNotEmpty(request.getRandomSeed())
                ? request.getRandomSeed()
                : System.nanoTime();

        List<MonteCarloEngine.TradeReturnSample> samples =
                monteCarloEngine.buildSamples(trades, backtestRun.getInitialCapital());

        if (samples.size() < MIN_TRADES) {
            throw new IllegalStateException(
                    "Insufficient valid trade samples after filtering. Found: " + samples.size());
        }

        UUID monteCarloRunId = UUID.randomUUID();

        log.info("Monte Carlo starting | runId={} mode={} simulations={} trades={} seed={}",
                monteCarloRunId, request.getSimulationMode(),
                request.getNumberOfSimulations(), samples.size(), effectiveSeed);

        MonteCarloResponse response = monteCarloEngine.run(
                samples, request, initialCapital, effectiveSeed, monteCarloRunId,
                backtestRun.getBacktestRunId(),
                backtestRun.getInitialCapital()
        );

        persistRun(response, request, backtestRun, effectiveSeed, monteCarloRunId);

        log.info("Monte Carlo completed | runId={} pRuin={} pProfit={} medianReturn={}%",
                monteCarloRunId, response.getProbabilityOfRuin(),
                response.getProbabilityOfProfit(), response.getMedianTotalReturnPct());

        return response;
    }


    private void persistRun(
            MonteCarloResponse response,
            MonteCarloRequest request,
            BacktestRun backtestRun,
            long effectiveSeed,
            UUID monteCarloRunId
    ) {
        try {
            Map<String, BigDecimal> pct = response.getFinalEquityPercentiles();

            MonteCarloRun run = MonteCarloRun.builder()
                    .monteCarloRunId(monteCarloRunId)
                    .backtestRunId(backtestRun.getBacktestRunId())
                    .accountStrategyId(backtestRun.getAccountStrategyId())
                    .simulationMode(request.getSimulationMode().name())
                    .numberOfSimulations(request.getNumberOfSimulations())
                    .tradesUsed(response.getTradesUsed())
                    .horizonTrades(request.getHorizonTrades())
                    .initialCapital(response.getInitialCapital())
                    .ruinThresholdPct(request.getRuinThresholdPct())
                    .maxAcceptableDrawdownPct(request.getMaxAcceptableDrawdownPct())
                    .randomSeed(request.getRandomSeed())
                    .effectiveSeed(effectiveSeed)
                    .meanFinalEquity(response.getMeanFinalEquity())
                    .medianFinalEquity(response.getMedianFinalEquity())
                    .minFinalEquity(response.getMinFinalEquity())
                    .maxFinalEquity(response.getMaxFinalEquity())
                    .p5FinalEquity(ObjectUtils.isNotEmpty(pct) ? pct.get("P5") : null)
                    .p25FinalEquity(ObjectUtils.isNotEmpty(pct) ? pct.get("P25") : null)
                    .p75FinalEquity(ObjectUtils.isNotEmpty(pct) ? pct.get("P75") : null)
                    .p95FinalEquity(ObjectUtils.isNotEmpty(pct) ? pct.get("P95") : null)
                    .meanTotalReturnPct(response.getMeanTotalReturnPct())
                    .medianTotalReturnPct(response.getMedianTotalReturnPct())
                    .meanMaxDrawdownPct(response.getMeanMaxDrawdownPct())
                    .medianMaxDrawdownPct(response.getMedianMaxDrawdownPct())
                    .worstMaxDrawdownPct(response.getWorstMaxDrawdownPct())
                    .probabilityOfRuin(response.getProbabilityOfRuin())
                    .probabilityOfDrawdownBreach(response.getProbabilityOfDrawdownBreach())
                    .probabilityOfProfit(response.getProbabilityOfProfit())
                    .status("COMPLETED")
                    .build();

            monteCarloRunRepository.save(run);

        } catch (Exception e) {
            // Persistence failure is non-fatal — the simulation result is already computed.
            log.warn("Failed to persist MonteCarloRun | runId={} error={}",
                    monteCarloRunId, e.getMessage());
        }
    }

    private void validateRequest(MonteCarloRequest request) {
        if (ObjectUtils.isEmpty(request)) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (ObjectUtils.isEmpty(request.getBacktestRunId())) {
            throw new IllegalArgumentException("backtestRunId cannot be null");
        }
        if (ObjectUtils.isEmpty(request.getSimulationMode())) {
            throw new IllegalArgumentException("simulationMode cannot be null");
        }

        int n = request.getNumberOfSimulations();
        if (n < 100 || n > MAX_SIMULATIONS) {
            throw new IllegalArgumentException(
                    "numberOfSimulations must be between 100 and " + MAX_SIMULATIONS + ". Got: " + n);
        }

        BigDecimal ruin = request.getRuinThresholdPct();
        if (ObjectUtils.isEmpty(ruin) || ruin.compareTo(BigDecimal.ZERO) <= 0 || ruin.compareTo(new BigDecimal("100")) >= 0) {
            throw new IllegalArgumentException("ruinThresholdPct must be between 0 and 100 exclusive");
        }

        BigDecimal dd = request.getMaxAcceptableDrawdownPct();
        if (ObjectUtils.isEmpty(dd) || dd.compareTo(BigDecimal.ZERO) <= 0 || dd.compareTo(new BigDecimal("100")) >= 0) {
            throw new IllegalArgumentException("maxAcceptableDrawdownPct must be between 0 and 100 exclusive");
        }

        if (ObjectUtils.isNotEmpty(request.getInitialCapital()) && request.getInitialCapital().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive if provided");
        }

        if (ObjectUtils.isNotEmpty(request.getHorizonTrades()) && request.getHorizonTrades() < 1) {
            throw new IllegalArgumentException("horizonTrades must be at least 1 if provided");
        }
    }
}