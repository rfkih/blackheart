package id.co.blackheart.dto.response;

import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveTradeResponse {
    private Trades trade;
    private List<TradePosition> positions;
}