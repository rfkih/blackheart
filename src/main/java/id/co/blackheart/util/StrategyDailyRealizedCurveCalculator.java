package id.co.blackheart.util;

import id.co.blackheart.dto.DailyPositionAggregateDto;
import id.co.blackheart.model.StrategyDailyRealizedCurve;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class StrategyDailyRealizedCurveCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int SCALE = 12;
    private static final String CALCULATION_VERSION = "v1";

    public StrategyDailyRealizedCurve calculate(
            UUID curveId,
            LocalDate curveDate,
            StrategyDailyRealizedCurve previousCurve,
            StrategyDailyRealizedCurve currentCurveOrNull,
            DailyPositionAggregateDto aggregate
    ) {
        BigDecimal previousCumulativePnl = previousCurve != null
                ? safe(previousCurve.getCumulativeRealizedPnlAmount())
                : ZERO;

        BigDecimal previousIndex = previousCurve != null
                ? safe(previousCurve.getCumulativeWeightedReturnIndex())
                : ONE;

        BigDecimal dailyRealizedPnlAmount = safe(aggregate.getDailyRealizedPnlAmount());
        BigDecimal dailyClosedNotional = safe(aggregate.getDailyClosedNotional());

        BigDecimal dailyWeightedReturnPct = ZERO;
        if (dailyClosedNotional.compareTo(ZERO) > 0) {
            dailyWeightedReturnPct = dailyRealizedPnlAmount.divide(dailyClosedNotional, SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal cumulativeRealizedPnlAmount = previousCumulativePnl.add(dailyRealizedPnlAmount);
        BigDecimal cumulativeWeightedReturnIndex = previousIndex.multiply(ONE.add(dailyWeightedReturnPct))
                .setScale(SCALE, RoundingMode.HALF_UP);

        return StrategyDailyRealizedCurve.builder()
                .strategyDailyRealizedCurveId(currentCurveOrNull != null
                        ? currentCurveOrNull.getStrategyDailyRealizedCurveId()
                        : curveId)
                .accountId(aggregate.getAccountId())
                .accountStrategyId(aggregate.getAccountStrategyId())
                .curveDate(curveDate)
                .dailyRealizedPnlAmount(dailyRealizedPnlAmount)
                .cumulativeRealizedPnlAmount(cumulativeRealizedPnlAmount)
                .dailyClosedNotional(dailyClosedNotional)
                .dailyWeightedReturnPct(dailyWeightedReturnPct)
                .cumulativeWeightedReturnIndex(cumulativeWeightedReturnIndex)
                .closedPositionCount(defaultInt(aggregate.getClosedPositionCount()))
                .winPositionCount(defaultInt(aggregate.getWinPositionCount()))
                .lossPositionCount(defaultInt(aggregate.getLossPositionCount()))
                .breakevenPositionCount(defaultInt(aggregate.getBreakevenPositionCount()))
                .calculationVersion(CALCULATION_VERSION)
                .build();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private Integer defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
