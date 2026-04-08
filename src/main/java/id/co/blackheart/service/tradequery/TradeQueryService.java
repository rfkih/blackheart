package id.co.blackheart.service.tradequery;

import id.co.blackheart.dto.response.ActiveTradeResponse;
import id.co.blackheart.model.Trades;
import id.co.blackheart.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeQueryService {

    private final CacheService cacheService;

    public List<ActiveTradeResponse> getActiveTradesByAccountId(UUID accountId) {
        List<ActiveTradeResponse> results = new ArrayList<>();

        for (UUID tradeId : cacheService.getAccountActiveTradeIds(accountId)) {
            try {
                Trades trade = cacheService.getTrade(tradeId);

                if (trade == null) {
                    continue;
                }

                results.add(
                        ActiveTradeResponse.builder()
                                .trade(trade)
                                .positions(cacheService.getTradePositions(tradeId))
                                .build()
                );
            } catch (Exception e) {
                log.error("Failed to get active trade for accountId={} tradeId={}", accountId, tradeId, e);
            }
        }

        results.sort(Comparator.comparing(
                item -> item.getTrade().getEntryTime(),
                Comparator.nullsLast(Comparator.reverseOrder())
        ));

        return results;
    }
}
