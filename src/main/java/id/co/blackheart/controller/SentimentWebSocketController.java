package id.co.blackheart.controller;

import id.co.blackheart.dto.response.SentimentResponse;
import id.co.blackheart.service.websocket.SentimentPublisherService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

/**
 * STOMP WebSocket controller for real-time combined 1h+4h market sentiment.
 *
 * <h3>Protocol</h3>
 * <pre>
 * Connect:      STOMP  ws://host/ws
 * Subscribe:    /topic/sentiment/BTCUSDT
 * Send:         /app/sentiment.subscribe    { "symbol": "BTCUSDT" }
 * Unsubscribe:  /app/sentiment.unsubscribe  { "symbol": "BTCUSDT" }
 * </pre>
 *
 * <p>On subscribe, the current snapshot is immediately pushed to
 * {@code /topic/sentiment/{symbol}}. Subsequent updates arrive every 15 seconds
 * only when the underlying 1h or 4h FeatureStore candle has changed.
 *
 * <h3>Sentiment labels (combined 1h+4h)</h3>
 * <pre>
 *   STRONG_BUY  — combined score >= 0.65
 *   BUY         — combined score >= 0.30
 *   NEUTRAL     — combined score in (-0.30, 0.30)
 *   SELL        — combined score <= -0.30
 *   STRONG_SELL — combined score <= -0.65
 * </pre>
 *
 * <h3>Score weighting</h3>
 * <pre>
 *   combined = (4h score × 0.60) + (1h score × 0.40)
 * </pre>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@Profile("!research")
public class SentimentWebSocketController {

    private final SentimentPublisherService sentimentPublisherService;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String TOPIC_PREFIX = "/topic/sentiment/";

    @MessageMapping("/sentiment.subscribe")
    public void subscribe(SentimentSubscribeRequest request) {
        if (request == null || !StringUtils.hasText(request.getSymbol())) {
            return;
        }

        String symbol = request.getSymbol().toUpperCase();
        sentimentPublisherService.addSubscription(symbol);

        // Immediate snapshot — cache hit if warm, single DB pair if cold
        SentimentResponse snapshot = sentimentPublisherService.getOrComputeLatest(symbol);
        messagingTemplate.convertAndSend(TOPIC_PREFIX + symbol, snapshot);

        log.info("Sentiment subscribe + snapshot | symbol={} sentiment={} score={} (1h={} 4h={})",
                symbol, snapshot.getSentiment(), snapshot.getScore(),
                snapshot.getScore1h(), snapshot.getScore4h());
    }

    @MessageMapping("/sentiment.unsubscribe")
    public void unsubscribe(SentimentUnsubscribeRequest request) {
        if (request == null || request.getSymbol() == null) {
            return;
        }
        sentimentPublisherService.removeSubscription(request.getSymbol().toUpperCase());
        log.info("Sentiment unsubscribe | symbol={}", request.getSymbol());
    }

    // ── Request payloads ──────────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SentimentSubscribeRequest {
        /** Trading symbol, e.g. "BTCUSDT" */
        private String symbol;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SentimentUnsubscribeRequest {
        private String symbol;
    }
}
