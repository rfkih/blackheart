package id.co.blackheart.service.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.TradePosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class RedisPublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPublisher(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishTradeStateChange(UUID tradeId, String tradeState) {
        String message = "Trade " + tradeId + " state changed to: " + tradeState;
        redisTemplate.convertAndSend("tradeStateUpdates", message);
    }

    public void publishTradePositionUpdate(UUID tradeId, TradePosition tradePosition) {
        String positionJson;
        try {
            positionJson = objectMapper.writeValueAsString(tradePosition);
        } catch (JsonProcessingException e) {
            log.warn("[RedisPublisher] Failed to serialize TradePosition | tradeId={} tradePositionId={}",
                    tradeId, tradePosition != null ? tradePosition.getTradePositionId() : "null");
            positionJson = "{\"tradeId\":\"" + tradeId + "\",\"error\":\"serialization_failed\"}";
        }
        redisTemplate.convertAndSend("tradePositionUpdates", positionJson);
    }
}
