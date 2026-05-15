package id.co.blackheart.service.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RedisSubscriber implements MessageListener {

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String body = new String(message.getBody());

        handleTradeStateChange(channel, body);
    }

    public void handleTradeStateChange(String channel, String message) {
        // Handle trade state change messages
        if ("tradeStateUpdates".equals(channel)) {
            log.info("Received trade state update: {}", message);
            // Forward this update to the frontend (e.g., via WebSocket or REST API)
        }

        // Handle trade position update messages
        if ("tradePositionUpdates".equals(channel)) {
            log.info("Received trade position update: {}", message);
            // Forward this update to the frontend (e.g., via WebSocket or REST API)
        }
    }
}
