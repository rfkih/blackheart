package id.co.blackheart.service.cache;

import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    public CacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void cacheActiveTrade(UUID tradeId, Trades trade) {
        String tradeKey = "trade:" + tradeId;

        redisTemplate.opsForHash().put(tradeKey, "status", trade.getStatus());
        redisTemplate.opsForHash().put(tradeKey, "avgEntryPrice", trade.getAvgEntryPrice());
        redisTemplate.opsForHash().put(tradeKey, "totalEntryQty", trade.getTotalEntryQty());
        redisTemplate.opsForHash().put(tradeKey, "totalRemainingQty", trade.getTotalRemainingQty());
    }

    public void cacheTradePositions(UUID tradeId, List<TradePosition> tradePositions) {
        String tradePositionsKey = "tradePositions:" + tradeId;
        redisTemplate.opsForList().rightPushAll(tradePositionsKey, tradePositions);
    }

    public Trades getTrade(UUID tradeId) {
        String tradeKey = "trade:" + tradeId;

        Map<Object, Object> tradeData = redisTemplate.opsForHash().entries(tradeKey);

        if (tradeData.isEmpty()) {
            return null;
        }

        Trades trade = new Trades();
        trade.setTradeId(tradeId);
        trade.setStatus((String) tradeData.get("status"));
        trade.setAvgEntryPrice((BigDecimal) tradeData.get("avgEntryPrice"));
        trade.setTotalEntryQty((BigDecimal) tradeData.get("totalEntryQty"));
        trade.setTotalRemainingQty((BigDecimal) tradeData.get("totalRemainingQty"));
        return trade;
    }

    // Get the list of TradePositions for a given tradeId
    public List<TradePosition> getTradePositions(UUID tradeId) {
        String tradePositionsKey = "tradePositions:" + tradeId;

        // Retrieve trade positions from Redis list
        List<Object> positions = redisTemplate.opsForList().range(tradePositionsKey, 0, -1);
        return positions == null ? List.of() : positions.stream()
                .map(position -> (TradePosition) position)
                .collect(Collectors.toList());
    }

    public void removeClosedTrade(UUID tradeId) {
        String tradeKey = "trade:" + tradeId;
        redisTemplate.delete(tradeKey);

        String tradePositionsKey = "tradePositions:" + tradeId;
        redisTemplate.delete(tradePositionsKey);
    }
}