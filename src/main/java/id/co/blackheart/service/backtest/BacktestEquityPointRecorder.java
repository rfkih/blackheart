package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestEquityPoint;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTradePosition;
import id.co.blackheart.model.MarketData;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BacktestEquityPointRecorder {

    public void recordPoint(
            BacktestState state,
            BacktestRun backtestRun,
            MarketData monitorCandle
    ) {
        if (state == null || backtestRun == null || monitorCandle == null) {
            return;
        }

        if (state.getEquityPoints() == null) {
            state.setEquityPoints(new ArrayList<>());
        }

        LocalDateTime candleTime = monitorCandle.getEndTime();
        if (candleTime == null) {
            return;
        }

        LocalDate equityDate = candleTime.toLocalDate();

        BigDecimal cashBalance = safe(state.getCashBalance());
        BigDecimal totalEquity = safe(state.getCurrentEquity());
        BigDecimal assetValue = totalEquity.subtract(cashBalance).max(BigDecimal.ZERO);
        int openPositions = countOpenPositions(state);

        // Current drawdown from peak (not running max) for meaningful per-day drawdown visualization
        BigDecimal currentDrawdown = BigDecimal.ZERO;
        if (state.getPeakEquity() != null && state.getPeakEquity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal dd = state.getPeakEquity()
                    .subtract(totalEquity)
                    .divide(state.getPeakEquity(), 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            currentDrawdown = dd.max(BigDecimal.ZERO);
        }

        LocalDate previousDate = state.getEquityPointIndex().lowerKey(equityDate);
        BacktestEquityPoint previousDayPoint = previousDate != null
                ? state.getEquityPointIndex().get(previousDate)
                : null;
        BigDecimal dailyReturnPct = calculateDailyReturnPct(previousDayPoint, totalEquity);

        BacktestEquityPoint existing = state.getEquityPointIndex().get(equityDate);
        UUID pointId = existing != null ? existing.getBacktestEquityPointId() : UUID.randomUUID();
        LocalDateTime createdAt = existing != null ? existing.getCreatedTime() : LocalDateTime.now();

        BacktestEquityPoint point = BacktestEquityPoint.builder()
                .backtestEquityPointId(pointId)
                .backtestRunId(backtestRun.getBacktestRunId())
                .accountId(resolveAccountId(backtestRun))
                .equityDate(equityDate)
                .cashBalance(cashBalance)
                .assetValue(assetValue)
                .totalEquity(totalEquity)
                .drawdownPercent(currentDrawdown)
                .dailyReturnPct(dailyReturnPct)
                .openPositions(openPositions)
                .build();

        point.setCreatedTime(createdAt);

        state.getEquityPointIndex().put(equityDate, point);
    }

    private BigDecimal calculateDailyReturnPct(BacktestEquityPoint previousDayPoint, BigDecimal currentTotalEquity) {
        if (previousDayPoint == null || previousDayPoint.getTotalEquity() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal previousEquity = previousDayPoint.getTotalEquity();
        if (previousEquity.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return currentTotalEquity.subtract(previousEquity)
                .divide(previousEquity, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private UUID resolveAccountId(BacktestRun backtestRun) {
        return backtestRun.getAccountStrategyId();
    }

    /**
     * Sum open positions across ALL active strategies in the multi-trade
     * map, not just the legacy single-slot mirror. Falls back to the
     * legacy slot when no multi-trade entries exist (single-strategy
     * runs / pre-B1 path) so the count is correct in both regimes.
     */
    private int countOpenPositions(BacktestState state) {
        Map<String, List<BacktestTradePosition>> byStrategy = state.getActiveTradePositionsByStrategy();
        if (!CollectionUtils.isEmpty(byStrategy)) {
            int count = 0;
            for (List<BacktestTradePosition> perStrategy : byStrategy.values()) {
                count += countOpenIn(perStrategy);
            }
            return count;
        }
        return countOpenIn(state.getActiveTradePositions());
    }

    private int countOpenIn(List<BacktestTradePosition> positions) {
        if (positions == null) return 0;
        int count = 0;
        for (BacktestTradePosition position : positions) {
            if (position != null && "OPEN".equalsIgnoreCase(position.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}