package id.co.blackheart.dto.tradelistener;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListenerContext {

    private String asset;
    private String interval;
    private PositionSnapshot positionSnapshot;
    private BigDecimal latestPrice;
}