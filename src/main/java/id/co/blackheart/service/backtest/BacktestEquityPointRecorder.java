package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestEquityPoint;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTradePosition;
import id.co.blackheart.model.MarketData;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class BacktestEquityPointRecorder {

    public void record(
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

        BacktestEquityPoint previousDayPoint = findPreviousDayPoint(state.getEquityPoints(), equityDate);
        BigDecimal dailyReturnPct = calculateDailyReturnPct(previousDayPoint, totalEquity);

        BacktestEquityPoint point = BacktestEquityPoint.builder()
                .backtestEquityPointId(UUID.randomUUID())
                .backtestRunId(backtestRun.getBacktestRunId())
                .accountId(resolveAccountId(backtestRun))
                .equityDate(equityDate)
                .cashBalance(cashBalance)
                .assetValue(assetValue)
                .totalEquity(totalEquity)
                .drawdownPercent(safe(state.getMaxDrawdownPercent()))
                .dailyReturnPct(dailyReturnPct)
                .openPositions(openPositions)
                .createdAt(LocalDateTime.now())
                .build();

        upsertDailyPoint(state.getEquityPoints(), point);
    }

    private void upsertDailyPoint(List<BacktestEquityPoint> equityPoints, BacktestEquityPoint newPoint) {
        for (int i = 0; i < equityPoints.size(); i++) {
            BacktestEquityPoint existing = equityPoints.get(i);
            if (existing != null && newPoint.getEquityDate().equals(existing.getEquityDate())) {
                newPoint.setBacktestEquityPointId(existing.getBacktestEquityPointId());
                newPoint.setCreatedAt(existing.getCreatedAt());
                equityPoints.set(i, newPoint);
                return;
            }
        }

        equityPoints.add(newPoint);
    }

    private BacktestEquityPoint findPreviousDayPoint(List<BacktestEquityPoint> equityPoints, LocalDate currentDate) {
        if (equityPoints == null || equityPoints.isEmpty()) {
            return null;
        }

        BacktestEquityPoint candidate = null;
        for (BacktestEquityPoint point : equityPoints) {
            if (point == null || point.getEquityDate() == null) {
                continue;
            }

            if (point.getEquityDate().isBefore(currentDate)) {
                if (candidate == null || point.getEquityDate().isAfter(candidate.getEquityDate())) {
                    candidate = point;
                }
            }
        }

        return candidate;
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

    private int countOpenPositions(BacktestState state) {
        List<BacktestTradePosition> positions = state.getActiveTradePositions();
        if (positions == null || positions.isEmpty()) {
            return 0;
        }

        return (int) positions.stream()
                .filter(position -> position != null)
                .filter(position -> "OPEN".equalsIgnoreCase(position.getStatus()))
                .count();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}