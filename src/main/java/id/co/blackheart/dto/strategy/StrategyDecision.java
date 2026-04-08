package id.co.blackheart.dto.strategy;

import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class StrategyDecision {

    /**
     * Final action after signal + veto + risk evaluation.
     */
    private DecisionType decisionType;

    /**
     * Strategy identity.
     */
    private String strategyCode;
    private String strategyName;
    private String strategyVersion;
    private String strategyInterval;

    /**
     * Signal classification.
     */
    private String signalType;       // TREND_PULLBACK, BREAKOUT, MEAN_REVERSION, EXIT_TRAIL, EXIT_STOP
    private String setupType;        // BULL_PULLBACK_RECLAIM, BEAR_BREAKDOWN_CONTINUATION, etc.
    private String side;             // LONG / SHORT
    private String regimeLabel;      // BULL_TREND, BEAR_TREND, RANGE, COMPRESSION, PANIC_HIGH_VOL
    private String marketStateLabel; // optional finer label

    /**
     * Explanation / attribution.
     */
    private String reason;
    private String exitReason;
    private String vetoReason;
    private List<String> tags;

    /**
     * Decision quality.
     */
    private BigDecimal signalScore;
    private BigDecimal confidenceScore;
    private BigDecimal regimeScore;
    private BigDecimal riskMultiplier;
    private BigDecimal volatilityScore;
    private BigDecimal jumpRiskScore;
    private BigDecimal liquidityScore;
    private Boolean vetoed;

    /**
     * Trade linkage.
     */
    private UUID tradeId;
    private UUID tradePositionId;
    private UUID accountStrategyId;

    /**
     * Execution intent.
     */
    private BigDecimal positionSize;
    private BigDecimal notionalSize;
    private BigDecimal stopLossPrice;
    private BigDecimal trailingStopPrice;
    private BigDecimal takeProfitPrice1;
    private BigDecimal takeProfitPrice2;
    private BigDecimal takeProfitPrice3;

    /**
     * Exit / child position structure.
     * SINGLE, TP1_RUNNER, TP1_TP2_RUNNER, RUNNER_ONLY
     */
    private String exitStructure;

    /**
     * ALL, SINGLE, TP1, TP2, TP3, RUNNER
     */
    private String targetPositionRole;

    /**
     * Risk / timing controls.
     */
    private Integer maxHoldingBars;
    private Integer cooldownBars;
    private LocalDateTime decisionTime;

    /**
     * Entry state snapshot for attribution.
     */
    private BigDecimal entryAdx;
    private BigDecimal entryAtr;
    private BigDecimal entryRsi;
    private String entryTrendRegime;

    /**
     * Flexible diagnostics for research / logging.
     */
    private Map<String, Object> diagnostics;

    public boolean isNoAction() {
        return decisionType == null
                || DecisionType.HOLD.equals(decisionType)
                || Boolean.TRUE.equals(vetoed);
    }
}