package id.co.blackheart.service.marketquery;


import id.co.blackheart.dto.response.LatestPriceResponse;
import id.co.blackheart.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MarketQueryService {

    private final CacheService cacheService;

    public LatestPriceResponse getLatestPrice(String symbol) {
        BigDecimal price = cacheService.getLatestPrice(symbol);
        LocalDateTime updatedAt = cacheService.getLatestPriceUpdatedAt(symbol);

        return LatestPriceResponse.builder()
                .symbol(symbol)
                .price(price)
                .updatedAt(updatedAt)
                .build();
    }
}
