package id.co.blackheart.stream;

import id.co.blackheart.model.*;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.repository.UserStrategyRepository;
import id.co.blackheart.repository.UsersRepository;
import id.co.blackheart.service.live.LiveTradeListenerService;
import id.co.blackheart.service.live.LiveTradingCoordinatorService;
import id.co.blackheart.service.marketdata.MarketDataService;
import id.co.blackheart.service.technicalindicator.TechnicalIndicatorService;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceWebSocketClient {

    private static final String SYMBOL = "BTCUSDT";
    private static final String WS_BASE = "wss://stream.binance.com:9443";
    private static final String STREAMS = "btcusdt@kline_15m/btcusdt@kline_1h/btcusdt@kline_4h";
    private static final String BINANCE_WS_URL = WS_BASE + "/stream?streams=" + STREAMS;

    private static final Set<String> ACCEPTED_INTERVALS = Set.of("15m", "1h", "4h");

    private static final Duration STALE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final MarketDataRepository marketDataRepository;
    private final UsersRepository usersRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final LiveTradingCoordinatorService liveTradingCoordinatorService;
    private final LiveTradeListenerService liveTradeListenerService;
    private final UserStrategyRepository userStrategyRepository;

    private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

    private final ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "binance-ws-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private final Map<String, LocalDateTime> lastSavedEndTimeByInterval = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private volatile Instant lastMessageTime = Instant.now();

    private final AtomicReference<Disposable> socketDisposable = new AtomicReference<>();
    private final AtomicReference<Disposable> reconnectDisposable = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> watchdogFuture = new AtomicReference<>();

    @PostConstruct
    public synchronized void start() {
        if (running) {
            log.warn("BinanceWebSocketClient is already running.");
            return;
        }

        running = true;
        lastMessageTime = Instant.now();

        log.info("Starting BinanceWebSocketClient for {}", SYMBOL);
        connect();
        startWatchdog();
    }

    @PreDestroy
    public synchronized void stop() {
        if (!running) {
            return;
        }

        log.info("Stopping BinanceWebSocketClient...");
        running = false;

        cancelReconnect();
        disconnectSocket();
        stopWatchdog();
        shutdownWatchdogExecutor();

        log.info("BinanceWebSocketClient stopped.");
    }

    public synchronized void connect() {
        if (!running) {
            log.info("Client is not running. Connect skipped.");
            return;
        }

        if (isSocketConnected()) {
            log.debug("WebSocket already connected.");
            return;
        }

        log.info("Connecting to Binance WebSocket: {}", BINANCE_WS_URL);

        socketDisposable.set(webSocketClient.execute(
                URI.create(BINANCE_WS_URL),
                session -> session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .flatMap(this::handleMessage)
                        .onErrorResume(error -> {
                            log.error("WebSocket session error", error);
                            return Mono.empty();
                        })
                        .then()
        ).subscribe(
                null,
                error -> {
                    if (!running) {
                        return;
                    }
                    log.error("WebSocket connection error", error);
                    scheduleReconnect();
                },
                () -> {
                    if (!running) {
                        return;
                    }
                    log.warn("WebSocket connection closed. Scheduling reconnect...");
                    scheduleReconnect();
                }
        ));
    }

    private Mono<Void> handleMessage(String message) {
        try {
            lastMessageTime = Instant.now();

            JSONObject root = new JSONObject(message);
            JSONObject container = extractContainer(root);
            JSONObject kline = container.getJSONObject("k");

            String interval = kline.getString("i");
            boolean finalCandle = kline.getBoolean("x");

            BigDecimal latestPrice =  new BigDecimal(kline.getString("c"));

            liveTradeListenerService.process(SYMBOL, latestPrice);

            if (!isProcessable(interval, finalCandle)) {
                return Mono.empty();
            }

            MarketData incomingMarketData = buildMarketData(container, kline, interval);


            MarketData latestBeforeInsert = marketDataRepository.findLatestBySymbol(SYMBOL, interval);

            if (isDuplicateAgainstLatest(latestBeforeInsert, incomingMarketData)) {
                return Mono.empty();
            }

            marketDataService.backfillMissingCandlesBeforeInsert(
                    SYMBOL,
                    interval,
                    latestBeforeInsert,
                    incomingMarketData.getEndTime().atZone(UTC).toInstant()
            );

            boolean exists = marketDataRepository.existsBySymbolAndIntervalAndStartTime(
                    SYMBOL,
                    interval,
                    incomingMarketData.getStartTime()
            );

            if (!exists) {
                persistMarketData(interval, incomingMarketData);
            }

            processPostPersist(interval, incomingMarketData, incomingMarketData.getStartTime());

            return Mono.empty();

        } catch (Exception e) {
            log.error("Failed to process websocket message: {}", message, e);
            return Mono.empty();
        }
    }

    private JSONObject extractContainer(JSONObject root) {
        return root.has("data") ? root.getJSONObject("data") : root;
    }

    private boolean isDuplicateAgainstLatest(MarketData latestBeforeInsert, MarketData incomingMarketData) {
        if (latestBeforeInsert == null || latestBeforeInsert.getEndTime() == null) {
            return false;
        }

        boolean duplicate = Objects.equals(latestBeforeInsert.getEndTime(), incomingMarketData.getEndTime());

        if (duplicate) {
            log.debug("Duplicate candle detected. interval={} endTime={}",
                    incomingMarketData.getInterval(),
                    incomingMarketData.getEndTime());
        }

        return duplicate;
    }

    private boolean isProcessable(String interval, boolean finalCandle) {
        if (!ACCEPTED_INTERVALS.contains(interval)) {
            return false;
        }

        return finalCandle;
    }

    private MarketData buildMarketData(JSONObject container, JSONObject kline, String interval) {
        Instant startTime = Instant.ofEpochMilli(kline.getLong("t"));
        Instant endTime = Instant.ofEpochMilli(kline.getLong("T"));
        Instant eventTime = Instant.ofEpochMilli(container.getLong("E"));

        MarketData marketData = new MarketData();
        marketData.setSymbol(SYMBOL);
        marketData.setInterval(interval);
        marketData.setStartTime(LocalDateTime.ofInstant(startTime, UTC));
        marketData.setEndTime(LocalDateTime.ofInstant(endTime, UTC));
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

    private void persistMarketData(String interval, MarketData marketData) {
        marketDataRepository.save(marketData);
        lastSavedEndTimeByInterval.put(interval, marketData.getEndTime());

        log.info("Inserted candle | symbol={} interval={} endTime={} close={}",SYMBOL,interval,marketData.getEndTime(),marketData.getClosePrice());
    }

    private void processPostPersist(String interval, MarketData marketData, LocalDateTime startTime) {
        FeatureStore featureStore = null;

        if (requiresFeatureComputation(interval)) {
            featureStore = technicalIndicatorService.computeIndicatorsAndStore(SYMBOL, interval, startTime);
        }

        if (featureStore != null) {
            List<UserStrategy> activeStrategies = userStrategyRepository
                    .findByEnabledTrueAndIntervalName(interval);

            for (UserStrategy userStrategy : activeStrategies) {
                try {
                    Users user = usersRepository.findByUserId(userStrategy.getUserId());

                    if (user == null || !"1".equals(user.getIsActive())) {
                        continue;
                    }

                    liveTradingCoordinatorService.process(
                            user,
                            userStrategy,
                            SYMBOL,
                            interval,
                            marketData,
                            featureStore
                    );

                } catch (Exception e) {
                    log.error(
                            "Live strategy execution failed | userStrategyId={} symbol={} interval={}",
                            userStrategy.getUserStrategyId(),
                            SYMBOL,
                            interval,
                            e
                    );
                }
            }
        }


    }

    private boolean requiresFeatureComputation(String interval) {
        return "1h".equals(interval) || "4h".equals(interval) || "15m".equals(interval);
    }

    private void startWatchdog() {
        ScheduledFuture<?> existing = watchdogFuture.get();
        if (existing != null && !existing.isCancelled() && !existing.isDone()) {
            log.debug("Watchdog already running.");
            return;
        }

        ScheduledFuture<?> scheduled = watchdogExecutor.scheduleAtFixedRate(
                this::checkConnectionHealth,
                30,
                30,
                TimeUnit.SECONDS
        );

        watchdogFuture.set(scheduled);
        log.debug("Watchdog started.");
    }

    private void stopWatchdog() {
        ScheduledFuture<?> existing = watchdogFuture.getAndSet(null);
        if (existing == null) {
            return;
        }

        try {
            existing.cancel(true);
            log.debug("Watchdog stopped.");
        } catch (Exception e) {
            log.warn("Failed to stop watchdog cleanly", e);
        }
    }

    private void checkConnectionHealth() {
        if (!running) {
            return;
        }

        Duration silenceDuration = Duration.between(lastMessageTime, Instant.now());
        if (silenceDuration.compareTo(STALE_TIMEOUT) > 0) {
            log.warn("WebSocket connection stale for {} seconds. Scheduling reconnect...",
                    silenceDuration.getSeconds());
            scheduleReconnect();
        }
    }

    private synchronized void scheduleReconnect() {
        if (!running) {
            log.info("Client is stopping. Reconnect skipped.");
            return;
        }

        disconnectSocket();
        cancelReconnect();

        reconnectDisposable.set(Mono.delay(RECONNECT_DELAY)
                .filter(ignore -> running)
                .subscribe(
                        ignore -> {
                            log.info("Reconnecting to Binance WebSocket...");
                            connect();
                        },
                        error -> log.error("Reconnect scheduling failed", error)
                ));
    }

    private boolean isSocketConnected() {
        return socketDisposable.get() != null && !socketDisposable.get().isDisposed();
    }

    private void disconnectSocket() {
        try {
            if (socketDisposable.get() != null && !socketDisposable.get().isDisposed()) {
                socketDisposable.get().dispose();
                log.info("WebSocket subscription disposed.");
            }
        } catch (Exception e) {
            log.warn("Failed to dispose websocket subscription cleanly", e);
        }
    }

    private void cancelReconnect() {
        try {
            if (reconnectDisposable.get() != null && !reconnectDisposable.get().isDisposed()) {
                reconnectDisposable.get().dispose();
                log.debug("Reconnect subscription disposed.");
            }
        } catch (Exception e) {
            log.warn("Failed to dispose reconnect subscription cleanly", e);
        }
    }


    private void shutdownWatchdogExecutor() {
        try {
            watchdogExecutor.shutdown();
            if (!watchdogExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Watchdog executor did not terminate gracefully. Forcing shutdown...");
                watchdogExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            watchdogExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            log.warn("Interrupted while shutting down watchdog executor", e);
        } catch (Exception e) {
            log.warn("Unexpected error while shutting down watchdog executor", e);
        }
    }
}

