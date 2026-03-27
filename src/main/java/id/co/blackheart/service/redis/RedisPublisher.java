package id.co.blackheart.service.redis;

import id.co.blackheart.model.TradePosition;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RedisPublisher {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisPublisher(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Publish trade state change (e.g., when the trade is closed) to Redis channel
    public void publishTradeStateChange(UUID tradeId, String tradeState) {
        String message = "Trade " + tradeId + " state changed to: " + tradeState;
        redisTemplate.convertAndSend("tradeStateUpdates", message);  // Publish to "tradeStateUpdates" channel
    }

    // Publish when a trade position is updated
    public void publishTradePositionUpdate(UUID tradeId, TradePosition tradePosition) {
        String message = "Trade " + tradeId + " position updated: " + tradePosition.toString();
        redisTemplate.convertAndSend("tradePositionUpdates", message);  // Publish to "tradePositionUpdates" channel
    }
}
