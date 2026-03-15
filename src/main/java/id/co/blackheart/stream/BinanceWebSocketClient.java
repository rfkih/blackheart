package id.co.blackheart.stream;

import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.MarketDataService;
import id.co.blackheart.service.TechnicalIndicatorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceWebSocketClient {

    private static final String SYMBOL = "BTCUSDT";
    private static final String WS_BASE = "wss://stream.binance.com:9443";
    private static final String STREAMS = "btcusdt@kline_15m/btcusdt@kline_1h/btcusdt@kline_4h";
    private static final String BINANCE_WS_URL = WS_BASE + "/stream?streams=" + STREAMS;

    private static final Set<String> ACCEPTED_INTERVALS = Set.of("15m", "1h", "4h");
    private static final Duration TIMEOUT_DURATION = Duration.ofSeconds(30);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);

    private final MarketDataRepository marketDataRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorService technicalIndicatorService;

    private final ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "binance-ws-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, LocalDateTime> lastSavedEndTimeByInterval = new ConcurrentHashMap<>();

    private volatile Instant lastMessageTime = Instant.now();
    private volatile boolean isRunning = false;

    private volatile Disposable socketSubscription;
    private volatile ScheduledFuture<?> watchdogFuture;
    private volatile Disposable reconnectSubscription;

    @PostConstruct
    public synchronized void start() {
        if (isRunning) {
            log.warn("BinanceWebSocketClient already started.");
            return;
        }

        log.info("Starting BinanceWebSocketClient...");
        isRunning = true;
        lastMessageTime = Instant.now();

        connect();
        startWatchdog();
    }

    @PreDestroy
    public synchronized void stop() {
        log.info("Stopping BinanceWebSocketClient...");
        isRunning = false;

        disposeReconnectSubscription();
        disposeSocketSubscription();
        stopWatchdog();

        try {
            watchdog.shutdown();
            if (!watchdog.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Watchdog did not terminate gracefully. Forcing shutdown...");
                watchdog.shutdownNow();
            }
        } catch (InterruptedException e) {
            watchdog.shutdownNow();
            Thread.currentThread().interrupt();
            log.warn("Interrupted while shutting down watchdog", e);
        } catch (Exception e) {
            log.warn("Unexpected error while shutting down watchdog", e);
        }

        log.info("BinanceWebSocketClient stopped.");
    }

    public synchronized void connect() {
        if (!isRunning) {
            log.info("Client is not running. Skipping connect.");
            return;
        }

        if (socketSubscription != null && !socketSubscription.isDisposed()) {
            log.debug("WebSocket already connected.");
            return;
        }

        log.info("Connecting to Binance WebSocket: {}", BINANCE_WS_URL);

        socketSubscription = client.execute(
                URI.create(BINANCE_WS_URL),
                session -> session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .flatMap(this::handleMessageReactive)
                        .onErrorResume(ex -> {
                            log.error("WebSocket session stream error", ex);
                            return Mono.empty();
                        })
                        .then()
        ).subscribe(
                null,
                error -> {
                    if (!isRunning) {
                        return;
                    }
                    log.error("WebSocket connection error", error);
                    reconnectWithDelay();
                },
                () -> {
                    if (!isRunning) {
                        return;
                    }
                    log.warn("WebSocket connection closed. Scheduling reconnect...");
                    reconnectWithDelay();
                }
        );
    }

    private synchronized void reconnectWithDelay() {
        if (!isRunning) {
            log.info("Client is stopping. Reconnect skipped.");
            return;
        }

        disposeSocketSubscription();
        disposeReconnectSubscription();

        reconnectSubscription = Mono.delay(RECONNECT_DELAY)
                .filter(ignore -> isRunning)
                .subscribe(
                        ignore -> {
                            log.info("Reconnecting to Binance WebSocket...");
                            connect();
                        },
                        error -> log.error("Reconnect scheduling error", error)
                );
    }

    private void startWatchdog() {
        if (watchdogFuture != null && !watchdogFuture.isCancelled() && !watchdogFuture.isDone()) {
            log.debug("Watchdog already running.");
            return;
        }

        watchdogFuture = watchdog.scheduleAtFixedRate(() -> {
            if (!isRunning) {
                return;
            }

            Duration silence = Duration.between(lastMessageTime, Instant.now());
            if (silence.compareTo(TIMEOUT_DURATION) > 0) {
                log.warn("WebSocket connection appears stale ({} seconds). Triggering reconnect...",
                        silence.getSeconds());
                reconnectWithDelay();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopWatchdog() {
        if (watchdogFuture != null) {
            watchdogFuture.cancel(true);
        }
    }

    private void disposeSocketSubscription() {
        try {
            if (socketSubscription != null && !socketSubscription.isDisposed()) {
                socketSubscription.dispose();
                log.info("WebSocket subscription disposed.");
            }
        } catch (Exception e) {
            log.warn("Failed to dispose websocket subscription cleanly", e);
        }
    }

    private void disposeReconnectSubscription() {
        try {
            if (reconnectSubscription != null && !reconnectSubscription.isDisposed()) {
                reconnectSubscription.dispose();
                log.debug("Reconnect subscription disposed.");
            }
        } catch (Exception e) {
            log.warn("Failed to dispose reconnect subscription cleanly", e);
        }
    }

    private Mono<Void> handleMessageReactive(String message) {
        try {
            lastMessageTime = Instant.now();

            JSONObject root = new JSONObject(message);
            JSONObject container = root.has("data") ? root.getJSONObject("data") : root;
            JSONObject kline = container.getJSONObject("k");

            boolean isFinal = kline.getBoolean("x");
            String interval = kline.getString("i");

            if (!ACCEPTED_INTERVALS.contains(interval)) {
                return Mono.empty();
            }


            if (!isFinal) {
                return Mono.empty();
            }

            log.info("[{}] Candle closed at {}", interval, kline.getString("c"));

            MarketData marketData = mapToMarketData(kline, container, interval);

            if (isDuplicateCandle(interval, marketData)) {
                return Mono.empty();
            }

            marketDataRepository.save(marketData);
            lastSavedEndTimeByInterval.put(interval, marketData.getEndTime());

            log.info("✅ Inserted {} {} candle: end={}, close={}", SYMBOL, interval, marketData.getEndTime(), marketData.getClosePrice());

            marketDataService.checkAndFetchMissingCandles(
                    SYMBOL,
                    marketData.getEndTime().atZone(ZoneId.of("UTC")).toInstant(),
                    interval
            );

            if ("1h".equals(interval) || "4h".equals(interval)) {
                technicalIndicatorService.computeIndicatorsAndStore(SYMBOL, interval);
            }

            return Mono.empty();

        } catch (Exception e) {
            log.error("Message processing error: {}", message, e);
            return Mono.empty();
        }
    }

    private MarketData mapToMarketData(JSONObject kline, JSONObject container, String interval) {
        Instant startTime = Instant.ofEpochMilli(kline.getLong("t"));
        Instant endTime = Instant.ofEpochMilli(kline.getLong("T"));
        Instant eventTime = Instant.ofEpochMilli(container.getLong("E"));

        MarketData marketData = new MarketData();
        marketData.setSymbol(SYMBOL);
        marketData.setInterval(interval);
        marketData.setStartTime(LocalDateTime.ofInstant(startTime, ZoneId.of("UTC")));
        marketData.setEndTime(LocalDateTime.ofInstant(endTime, ZoneId.of("UTC")));
        marketData.setOpenPrice(new BigDecimal(kline.getString("o")));
        marketData.setHighPrice(new BigDecimal(kline.getString("h")));
        marketData.setLowPrice(new BigDecimal(kline.getString("l")));
        marketData.setClosePrice(new BigDecimal(kline.getString("c")));
        marketData.setVolume(new BigDecimal(kline.getString("v")));
        marketData.setQuoteAssetVolume(new BigDecimal(kline.getString("q")));
        marketData.setTakerBuyBaseVolume(new BigDecimal(kline.getString("V")));
        marketData.setTakerBuyQuoteVolume(new BigDecimal(kline.getString("Q")));
        marketData.setTradeCount(kline.getLong("n"));
        marketData.setCreatedTime(eventTime);

        return marketData;
    }

    private boolean isDuplicateCandle(String interval, MarketData marketData) {
        LocalDateTime lastEnd = lastSavedEndTimeByInterval.get(interval);
        if (Objects.equals(lastEnd, marketData.getEndTime())) {
            log.debug("⏩ Duplicate {} candle in memory. Skipping: {}", interval, marketData.getEndTime());
            return true;
        }

        MarketData latest = marketDataRepository.findLatestBySymbol(SYMBOL, interval);
        if (latest != null && latest.getEndTime() != null
                && latest.getEndTime().equals(marketData.getEndTime())) {
            log.debug("⏩ Duplicate {} candle in DB. Skipping: {}", interval, marketData.getEndTime());
            lastSavedEndTimeByInterval.put(interval, marketData.getEndTime());
            return true;
        }

        return false;
    }
}