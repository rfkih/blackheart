package id.co.blackheart.dto.tradelistener;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.model.MarketData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListenerContext {

    private String asset;
    private String interval;
    private PositionSnapshot positionSnapshot;
    private MarketData monitorCandle;
}