package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import org.springframework.stereotype.Service;

@Service
public class LivePositionSnapshotMapper {

    public PositionSnapshot toSnapshot(TradePosition tradePosition) {
        if (tradePosition == null) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        return PositionSnapshot.builder()
                .tradeId(tradePosition.getTradeId())
                .tradePositionId(tradePosition.getTradePositionId())
                .hasOpenPosition("OPEN".equalsIgnoreCase(tradePosition.getStatus()))
                .side(tradePosition.getSide())
                .status(tradePosition.getStatus())
                .positionRole(tradePosition.getPositionRole())
                .entryPrice(tradePosition.getEntryPrice())
                .entryQty(tradePosition.getEntryQty())
                .entryQuoteQty(tradePosition.getEntryQuoteQty())
                .remainingQty(tradePosition.getRemainingQty())
                .currentStopLossPrice(tradePosition.getCurrentStopLossPrice())
                .initialStopLossPrice(tradePosition.getInitialStopLossPrice())
                .trailingStopPrice(tradePosition.getTrailingStopPrice())
                .takeProfitPrice(tradePosition.getTakeProfitPrice())
                .highestPriceSinceEntry(tradePosition.getHighestPriceSinceEntry())
                .lowestPriceSinceEntry(tradePosition.getLowestPriceSinceEntry())
                .entryTime(tradePosition.getEntryTime())
                .build();
    }

    public PositionSnapshot toSnapshot(Trades trade) {
        if (trade == null) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        return PositionSnapshot.builder()
                .tradeId(trade.getTradeId())
                .hasOpenPosition("OPEN".equalsIgnoreCase(trade.getStatus())
                        || "PARTIALLY_CLOSED".equalsIgnoreCase(trade.getStatus()))
                .side(trade.getSide())
                .status(trade.getStatus())
                .entryPrice(trade.getAvgEntryPrice())
                .entryQty(trade.getTotalEntryQty())
                .entryQuoteQty(trade.getTotalEntryQuoteQty())
                .remainingQty(trade.getTotalRemainingQty())
                .entryTime(trade.getEntryTime())
                .build();
    }
}