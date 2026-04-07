package id.co.blackheart.dto.request;

import id.co.blackheart.util.MonteCarloSimulationMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonteCarloRequest {

    /**
     * Source of historical trade outcomes. Must reference a COMPLETED backtest run.
     */
    private UUID backtestRunId;

    /**
     * Starting capital for each simulation path.
     * If null, defaults to the referenced backtest run's initial capital.
     */
    private BigDecimal initialCapital;

    private MonteCarloSimulationMode simulationMode;

    /**
     * Number of simulation paths to generate. Range: 100–100,000.
     */
    private int numberOfSimulations;

    /**
     * Number of trades to simulate per path.
     * For BOOTSTRAP_RETURNS: number of samples drawn with replacement (null = same as historical count).
     * For TRADE_SEQUENCE_SHUFFLE: if provided, only the first N trades of each shuffle are used.
     */
    private Integer horizonTrades;

    /**
     * Additional percentile levels to include in the output (e.g. [0.99]).
     * Standard levels P5, P10, P25, P50, P75, P90, P95 are always included.
     */
    private List<BigDecimal> confidenceLevels;

    /**
     * A simulation path is "ruined" when equity falls below initialCapital * (1 - ruinThresholdPct / 100).
     * Example: 30 means ruin if equity drops more than 30% below initial.
     */
    private BigDecimal ruinThresholdPct;

    /**
     * Probability of exceeding this drawdown is reported separately.
     * Example: 20 means report P(maxDrawdown > 20%).
     */
    private BigDecimal maxAcceptableDrawdownPct;

    /**
     * Fixed seed for reproducible results. If null, a random seed is generated and echoed in the response.
     */
    private Long randomSeed;
}