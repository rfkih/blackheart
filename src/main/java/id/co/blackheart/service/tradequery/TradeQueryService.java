package id.co.blackheart.service.tradequery;


import id.co.blackheart.dto.response.ActiveTradeResponse;

import java.util.List;
import java.util.UUID;

public interface TradeQueryService {
    List<ActiveTradeResponse> getActiveTradesByUserId(UUID userId);
}