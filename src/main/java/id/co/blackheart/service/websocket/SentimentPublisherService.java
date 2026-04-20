package id.co.blackheart.service.websocket;

import id.co.blackheart.dto.response.SentimentResponse;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.repository.FeatureStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes combined 1h+4h sentiment updates to {@code /topic/sentiment/{symbol}}.
 *
 * <h3>Performance design</h3>
 * <ul>
 *   <li>Two DB queries per symbol per cycle (one per interval) regardless of subscriber count.</li>
 *   <li>Sentiment is only recomputed when the 1h <em>or</em> 4h {@link FeatureStore}
 *       {@code startTime} advances. Between candle closes the cached response is returned
 *       instantly — no scoring CPU, no object allocation.</li>
 *   <li>WebSocket broadcast only fires when data actually changed.</li>
 *   <li>{@link #getOrComputeLatest} returns the cached response with no DB access
 *       when the cache is warm — used by the subscribe handler for immediate snapshots.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentPublisherService {

    private static final String INTERVAL_1H  = "1h";
    private static final String INTERVAL_4H  = "4h";
    private static final String TOPIC_PREFIX = "/topic/sentiment/";

    private final SimpMessagingTemplate messagingTemplate;
    private final FeatureStoreRepository featureStoreRepository;
    private final SentimentAnalyzerService sentimentAnalyzerService;

    /** Symbols with at least one active subscriber. */
    private final Set<String> activeSubscriptions = ConcurrentHashMap.newKeySet();

    /** symbol → last published SentimentResponse */
    private final Map<String, SentimentResponse> responseCache = new ConcurrentHashMap<>();

    /** symbol → last seen 1h FeatureStore startTime */
    private final Map<String, LocalDateTime> lastFeatureTime1h = new ConcurrentHashMap<>();

    /** symbol → last seen 4h FeatureStore startTime */
    private final Map<String, LocalDateTime> lastFeatureTime4h = new ConcurrentHashMap<>();


    public void addSubscription(String symbol) {
        activeSubscriptions.add(symbol.toUpperCase());
        log.info("Sentiment subscription added: symbol={}", symbol);
    }

    public void removeSubscription(String symbol) {
        activeSubscriptions.remove(symbol.toUpperCase());
        log.info("Sentiment subscription removed: symbol={}", symbol);
    }

    /**
     * Returns the latest combined 1h+4h sentiment for the given symbol.
     * Returns the cached response instantly if the cache is warm (no DB access).
     * Performs a single pair of DB queries only on the first call (cold cache).
     */
    public SentimentResponse getOrComputeLatest(String symbol) {
        String key = symbol.toUpperCase();
        SentimentResponse cached = responseCache.get(key);
        if (cached != null) {
            return cached;
        }
        return computeAndCache(key);
    }


    /**
     * Runs every 15 seconds.
     * Queries the latest 1h and 4h FeatureStore for each subscribed symbol.
     * Recomputes and broadcasts only when at least one frame's {@code startTime} has advanced.
     */
    @Scheduled(fixedRate = 15_000)
    public void publishSentiment() {
        if (activeSubscriptions.isEmpty()) {
            return;
        }

        for (String symbol : activeSubscriptions) {
            try {
                FeatureStore latest1h = featureStoreRepository
                        .findLatestBySymbolAndInterval(symbol, INTERVAL_1H)
                        .orElse(null);
                FeatureStore latest4h = featureStoreRepository
                        .findLatestBySymbolAndInterval(symbol, INTERVAL_4H)
                        .orElse(null);

                LocalDateTime new1h = latest1h != null ? latest1h.getStartTime() : null;
                LocalDateTime new4h = latest4h != null ? latest4h.getStartTime() : null;

                boolean changed = !equals(new1h, lastFeatureTime1h.get(symbol))
                        || !equals(new4h, lastFeatureTime4h.get(symbol));

                if (!changed) {
                    log.debug("Sentiment unchanged | symbol={} 1h={} 4h={}", symbol, new1h, new4h);
                    continue;
                }

                SentimentResponse response = sentimentAnalyzerService.analyze(latest1h, latest4h, symbol);
                responseCache.put(symbol, response);
                if (new1h != null) lastFeatureTime1h.put(symbol, new1h);
                if (new4h != null) lastFeatureTime4h.put(symbol, new4h);

                messagingTemplate.convertAndSend(TOPIC_PREFIX + symbol, response);

                log.info("Sentiment broadcast | symbol={} sentiment={} score={} score1h={} score4h={} 1h={} 4h={}",
                        symbol, response.getSentiment(), response.getScore(),
                        response.getScore1h(), response.getScore4h(), new1h, new4h);

            } catch (Exception e) {
                log.error("Failed to publish sentiment | symbol={}", symbol, e);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SentimentResponse computeAndCache(String symbol) {
        try {
            FeatureStore fs1h = featureStoreRepository
                    .findLatestBySymbolAndInterval(symbol, INTERVAL_1H)
                    .orElse(null);
            FeatureStore fs4h = featureStoreRepository
                    .findLatestBySymbolAndInterval(symbol, INTERVAL_4H)
                    .orElse(null);

            SentimentResponse response = sentimentAnalyzerService.analyze(fs1h, fs4h, symbol);
            responseCache.put(symbol, response);
            if (fs1h != null && fs1h.getStartTime() != null) lastFeatureTime1h.put(symbol, fs1h.getStartTime());
            if (fs4h != null && fs4h.getStartTime() != null) lastFeatureTime4h.put(symbol, fs4h.getStartTime());
            return response;
        } catch (Exception e) {
            log.error("Failed to compute initial sentiment | symbol={}", symbol, e);
            return sentimentAnalyzerService.analyze(null, null, symbol);
        }
    }

    private static boolean equals(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
