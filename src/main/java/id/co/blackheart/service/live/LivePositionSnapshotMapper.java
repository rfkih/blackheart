package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.model.Trades;
import org.springframework.stereotype.Service;

@Service
public class LivePositionSnapshotMapper {

    public PositionSnapshot toSnapshot(Trades trade) {
        if (trade == null) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        return PositionSnapshot.builder()
                .hasOpenPosition(true)
                .side(trade.getSide())
                .status(trade.getStatus())
                .entryPrice(trade.getEntryPrice())
                .entryQty(trade.getEntryExecutedQty())
                .entryQuoteQty(trade.getEntryExecutedQuoteQty())
                .currentStopLossPrice(trade.getCurrentStopLossPrice())
                .initialStopLossPrice(trade.getInitialStopLossPrice())
                .trailingStopPrice(trade.getTrailingStopPrice())
                .takeProfitPrice(trade.getTakeProfitPrice())
                .entryTime(trade.getEntryTime())
                .build();
    }
}