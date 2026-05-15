package id.co.blackheart.service.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Bridges the Redis {@code research:activity} pub/sub channel to the STOMP
 * WebSocket topic {@code /topic/research/activity}.
 *
 * <p>The orchestrator publishes raw JSON strings on {@code research:activity}.
 * This listener receives them and forwards the bytes as-is to connected
 * STOMP clients — no Jackson round-trip, no type annotation overhead.
 *
 * <p>Runs on the Trading JVM only ({@code @Profile("!research")}) because the
 * Trading JVM owns the WebSocket broker and {@link SimpMessagingTemplate}.
 */
@Slf4j
@Service
@Profile("!research")
@RequiredArgsConstructor
public class ResearchActivityWebSocketPublisher implements MessageListener {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            // Deserialize to Map so SimpMessagingTemplate/Jackson re-serializes it
            // as a proper JSON object, not a double-encoded JSON string.
            Map<String, Object> payload = MAPPER.readValue(body, MAP_TYPE);
            messagingTemplate.convertAndSend("/topic/research/activity", payload);
            log.debug("Forwarded research activity event to WS topic");
        } catch (Exception e) {
            log.warn("Failed to forward research activity event to WS: {}", e.getMessage());
        }
    }
}
