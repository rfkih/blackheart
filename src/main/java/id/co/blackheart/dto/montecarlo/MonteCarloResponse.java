package id.co.blackheart.dto.montecarlo;

import id.co.blackheart.util.MonteCarloSimulationMode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class MonteCarloResponse {

    // ── Input summary ────────────────────────────────────────────────────────
    private UUID monteCarloRunId;
    private UUID backtestRunId;
    private MonteCarloSimulationMode simulationMode;
    private int numberOfSimulations;
    private int tradesUsed;
    private BigDecimal initialCapital;
    private BigDecimal ruinThresholdPct;
    private BigDecimal maxAcceptableDrawdownPct;
    private long effectiveSeed;

    // ── Source trade statistics ───────────────────────────────────────────────
    private BigDecimal sourceMeanTradePnl;       // mean per-trade PnL (in MC initial capital units)
    private BigDecimal sourceMedianTradePnl;
    private BigDecimal sourceStdDevTradePnl;
    private BigDecimal sourceWinRate;            // percentage 0–100

    // ── Final equity distribution ─────────────────────────────────────────────
    private BigDecimal meanFinalEquity;
    private BigDecimal medianFinalEquity;
    private BigDecimal minFinalEquity;
    private BigDecimal maxFinalEquity;

    /**
     * Keyed by "P5", "P10", "P25", "P50", "P75", "P90", "P95" plus any custom confidence levels.
     */
    private Map<String, BigDecimal> finalEquityPercentiles;

    // ── Return distribution ───────────────────────────────────────────────────
    private BigDecimal meanTotalReturnPct;
    private BigDecimal medianTotalReturnPct;
    private BigDecimal minTotalReturnPct;
    private BigDecimal maxTotalReturnPct;

    // ── Drawdown distribution ─────────────────────────────────────────────────
    private BigDecimal meanMaxDrawdownPct;
    private BigDecimal medianMaxDrawdownPct;
    private BigDecimal worstMaxDrawdownPct;

    // ── Risk metrics ──────────────────────────────────────────────────────────
    private BigDecimal probabilityOfRuin;            // fraction 0.0 – 1.0
    private BigDecimal probabilityOfDrawdownBreach;  // P(maxDD > maxAcceptableDrawdownPct)
    private BigDecimal probabilityOfProfit;          // P(finalEquity > initialCapital)

    // ── Selected simulation paths (equity curves attached) ───────────────────
    private MonteCarloPathSummary bestPath;
    private MonteCarloPathSummary medianPath;
    private MonteCarloPathSummary worstPath;
}