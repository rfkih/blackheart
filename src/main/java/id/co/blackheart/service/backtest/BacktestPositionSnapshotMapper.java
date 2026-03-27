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

    public PositionSnapshot toSnapshot(BacktestTrade trade) {
        if (trade == null) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        return PositionSnapshot.builder()
                .tradeId(trade.getBacktestTradeId())
                .hasOpenPosition(true)
                .side(trade.getSide())
                .status(trade.getStatus())
                .entryPrice(trade.getAvgEntryPrice())
                .entryQty(trade.getTotalEntryQty())
                .entryQuoteQty(trade.getTotalEntryQuoteQty())
                .entryTime(trade.getEntryTime())
                .build();
    }

    public PositionSnapshot toSnapshot(BacktestTrade trade, List<BacktestTradePosition> openPositions) {
        if (trade == null || openPositions == null || openPositions.isEmpty()) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        BacktestTradePosition primary = resolvePrimary(openPositions);

        BigDecimal totalRemainingQty = openPositions.stream()
                .map(BacktestTradePosition::getRemainingQty)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PositionSnapshot.builder()
                .tradeId(trade.getBacktestTradeId())
                .tradePositionId(primary.getTradePositionId())
                .hasOpenPosition(true)
                .side(trade.getSide())
                .status(trade.getStatus())
                .positionRole(primary.getPositionRole())
                .entryPrice(primary.getEntryPrice())
                .entryQty(primary.getEntryQty())
                .entryQuoteQty(primary.getEntryQuoteQty())
                .remainingQty(totalRemainingQty)
                .currentStopLossPrice(primary.getCurrentStopLossPrice())
                .initialStopLossPrice(primary.getInitialStopLossPrice())
                .trailingStopPrice(primary.getTrailingStopPrice())
                .takeProfitPrice(primary.getTakeProfitPrice())
                .highestPriceSinceEntry(resolveHighest(openPositions))
                .lowestPriceSinceEntry(resolveLowest(openPositions))
                .entryTime(primary.getEntryTime())
                .build();
    }

    public PositionSnapshot toSnapshot(BacktestTradePosition position) {
        if (position == null) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        return PositionSnapshot.builder()
                .tradeId(position.getTradeId())
                .tradePositionId(position.getTradePositionId())
                .hasOpenPosition(true)
                .side(position.getSide())
                .status(position.getStatus())
                .positionRole(position.getPositionRole())
                .entryPrice(position.getEntryPrice())
                .entryQty(position.getEntryQty())
                .entryQuoteQty(position.getEntryQuoteQty())
                .remainingQty(position.getRemainingQty())
                .currentStopLossPrice(position.getCurrentStopLossPrice())
                .initialStopLossPrice(position.getInitialStopLossPrice())
                .trailingStopPrice(position.getTrailingStopPrice())
                .takeProfitPrice(position.getTakeProfitPrice())
                .highestPriceSinceEntry(position.getHighestPriceSinceEntry())
                .lowestPriceSinceEntry(position.getLowestPriceSinceEntry())
                .entryTime(position.getEntryTime())
                .build();
    }

    private BacktestTradePosition resolvePrimary(List<BacktestTradePosition> openPositions) {
        return openPositions.stream()
                .min(Comparator.comparing(
                        BacktestTradePosition::getEntryTime,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .orElse(openPositions.getFirst());
    }

    private BigDecimal resolveHighest(List<BacktestTradePosition> openPositions) {
        return openPositions.stream()
                .map(BacktestTradePosition::getHighestPriceSinceEntry)
                .filter(v -> v != null)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private BigDecimal resolveLowest(List<BacktestTradePosition> openPositions) {
        return openPositions.stream()
                .map(BacktestTradePosition::getLowestPriceSinceEntry)
                .filter(v -> v != null)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }
}