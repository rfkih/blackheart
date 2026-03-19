package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.model.BacktestTrade;
import org.springframework.stereotype.Service;

@Service
public class BacktestPositionSnapshotMapper {

    public PositionSnapshot toSnapshot(BacktestTrade trade) {
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
                .entryQty(trade.getEntryQty())
                .entryQuoteQty(trade.getEntryQuoteQty())
                .currentStopLossPrice(trade.getCurrentStopLossPrice())
                .initialStopLossPrice(trade.getInitialStopLossPrice())
                .trailingStopPrice(trade.getTrailingStopPrice())
                .takeProfitPrice(trade.getTakeProfitPrice())
                .entryTime(trade.getEntryTime())
                .build();
    }
}