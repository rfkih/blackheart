package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@Service
public class BacktestPositionSnapshotMapper {

    public PositionSnapshot toSnapshot(
            BacktestTrade trade,
            List<BacktestTradePosition> openPositions
    ) {
        if (trade == null || openPositions == null || openPositions.isEmpty()) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        BigDecimal totalEntryQty = openPositions.stream()
                .map(BacktestTradePosition::getEntryQty)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalEntryQuoteQty = openPositions.stream()
                .map(BacktestTradePosition::getEntryQuoteQty)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRemainingQty = openPositions.stream()
                .map(BacktestTradePosition::getRemainingQty)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String side = trade.getSide();

        BigDecimal currentStopLossPrice = resolveEffectiveStop(openPositions, side);
        BigDecimal initialStopLossPrice = resolveEffectiveInitialStop(openPositions, side);
        BigDecimal trailingStopPrice = resolveEffectiveTrailingStop(openPositions, side);
        BigDecimal takeProfitPrice = resolveEffectiveTakeProfit(openPositions, side);

        BigDecimal highestPriceSinceEntry = openPositions.stream()
                .map(BacktestTradePosition::getHighestPriceSinceEntry)
                .filter(v -> v != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        BigDecimal lowestPriceSinceEntry = openPositions.stream()
                .map(BacktestTradePosition::getLowestPriceSinceEntry)
                .filter(v -> v != null)
                .min(Comparator.naturalOrder())
                .orElse(null);

        BacktestTradePosition representative = openPositions.get(0);

        return PositionSnapshot.builder()
                .tradeId(trade.getBacktestTradeId())
                .tradePositionId(representative.getTradePositionId())
                .hasOpenPosition(true)
                .side(side)
                .status(trade.getStatus())
                .positionRole(trade.getTradeMode())
                .entryPrice(trade.getAvgEntryPrice())
                .entryQty(totalEntryQty)
                .entryQuoteQty(totalEntryQuoteQty)
                .remainingQty(totalRemainingQty)
                .currentStopLossPrice(currentStopLossPrice)
                .initialStopLossPrice(initialStopLossPrice)
                .trailingStopPrice(trailingStopPrice)
                .takeProfitPrice(takeProfitPrice)
                .highestPriceSinceEntry(highestPriceSinceEntry)
                .lowestPriceSinceEntry(lowestPriceSinceEntry)
                .entryTime(trade.getEntryTime())
                .build();
    }

    private BigDecimal resolveEffectiveStop(List<BacktestTradePosition> positions, String side) {
        return positions.stream()
                .map(BacktestTradePosition::getCurrentStopLossPrice)
                .filter(v -> v != null)
                .min(stopComparator(side))
                .orElse(null);
    }

    private BigDecimal resolveEffectiveInitialStop(List<BacktestTradePosition> positions, String side) {
        return positions.stream()
                .map(BacktestTradePosition::getInitialStopLossPrice)
                .filter(v -> v != null)
                .min(stopComparator(side))
                .orElse(null);
    }

    private BigDecimal resolveEffectiveTrailingStop(List<BacktestTradePosition> positions, String side) {
        return positions.stream()
                .map(BacktestTradePosition::getTrailingStopPrice)
                .filter(v -> v != null)
                .min(stopComparator(side))
                .orElse(null);
    }

    private BigDecimal resolveEffectiveTakeProfit(List<BacktestTradePosition> positions, String side) {
        if ("SHORT".equalsIgnoreCase(side)) {
            return positions.stream()
                    .map(BacktestTradePosition::getTakeProfitPrice)
                    .filter(v -> v != null)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        }

        return positions.stream()
                .map(BacktestTradePosition::getTakeProfitPrice)
                .filter(v -> v != null)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private Comparator<BigDecimal> stopComparator(String side) {
        if ("SHORT".equalsIgnoreCase(side)) {
            return Comparator.reverseOrder();
        }
        return Comparator.naturalOrder();
    }
}