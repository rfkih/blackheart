package id.co.blackheart.stream;

import id.co.blackheart.model.*;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.service.cache.CacheService;
import id.co.blackheart.service.live.LiveOrchestratorCoordinatorService;
import id.co.blackheart.service.live.LiveTradeListenerService;
import id.co.blackheart.service.live.LiveTradingCoordinatorService;
import id.co.blackheart.service.marketdata.MarketDataService;
import id.co.blackheart.service.technicalindicator.TechnicalIndicatorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live trading entry point. Subscribes to Binance kline WebSocket and
 * dispatches closed-candle events into the live coordinator chain.
 *
 * <p><b>JVM-split safety (V14+):</b> annotated {@code @Profile("!research")}
 * so this bean does NOT register on a JVM started with
 * {@code spring.profiles.active=research}. Two simultaneous WS connections
 * to Binance would create duplicate signals and risk double-trading; gating
 * here prevents the research-isolated JVM from opening a second feed. See
 * {@code research/DEPLOYMENT.md} step 3 for the full two-JVM operating
 * model.
 */
@Slf4j
@Service
@Profile("!research")
@RequiredArgsConstructor
public class BinanceWebSocketClient {

    @Value("${app.live.binance-ws-base:wss://data-stream.binance.vision}")
    private final static String WS_BASE;

    /** Intervals subscribed on the kline stream. Order is irrelevant; iterate as a list to keep the URL deterministic for logs. */
    private static final List<String> SUBSCRIBED_INTERVALS = List.of("5m", "15m", "1h", "4h");
    private static final Set<String> ACCEPTED_INTERVALS = Set.copyOf(SUBSCRIBED_INTERVALS);

    /**
     * Trading pair this client subscribes to. Single-symbol today (BTCUSDT);
     * pulled out of a constant so adding ETHUSDT etc. is a config change
     * rather than a code edit. Multi-symbol routing is a separate, larger
     * change to this class — see SymbolUtils + the data-plane TODO.
     */
    @Value("${app.live.symbol:BTCUSDT}")
    private String symbol;

    private static final Duration STALE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final MarketDataRepository marketDataRepository;
    private final AccountRepository accountRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final LiveTradingCoordinatorService liveTradingCoordinatorService;
    private final LiveOrchestratorCoordinatorService liveOrchestratorCoordinatorService;
    private final LiveTradeListenerService liveTradeListenerService;
    private final AccountStrategyRepository accountStrategyRepository;
    private final CacheService cacheService;

    private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

    private final ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "binance-ws-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean running = false;
    private volatile Instant lastMessageTime = Instant.now();

    public boolean isRunning() {
        return running;
    }

    public Instant getLastMessageTime() {
        return lastMessageTime;
    }

    public String getSymbol() {
        return symbol;
    }

    public List<String> getSubscribedIntervals() {
        return SUBSCRIBED_INTERVALS;
    }

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

        log.info("Starting BinanceWebSocketClient for {}", symbol);
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

        String wsUrl = buildBinanceWsUrl();
        log.info("Connecting to Binance WebSocket: {}", wsUrl);

        socketDisposable.set(webSocketClient.execute(
                URI.create(wsUrl),
                session -> session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .flatMap(msg -> Mono.fromCallable(() -> {
                            handleMessage(msg);
                            return msg;
                        }).subscribeOn(Schedulers.boundedElastic()).then())
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

    private void handleMessage(String message) {
        try {
            lastMessageTime = Instant.now();

            JSONObject root = new JSONObject(message);
            JSONObject container = extractContainer(root);
            JSONObject kline = container.getJSONObject("k");

            String interval = kline.getString("i");
            boolean finalCandle = kline.getBoolean("x");

            BigDecimal latestPrice = new BigDecimal(kline.getString("c"));

            if ("5m".equals(interval)) {
                cacheService.saveLatestPrice(symbol, latestPrice, LocalDateTime.now());
                liveTradeListenerService.process(symbol, latestPrice);
            }

            if (!isProcessable(interval, finalCandle)) {
                return;
            }

            MarketData incomingMarketData = buildMarketData(container, kline, interval);

            MarketData latestBeforeInsert = marketDataRepository.findLatestBySymbol(symbol, interval);

            if (isDuplicateAgainstLatest(latestBeforeInsert, incomingMarketData)) {
                return;
            }

            marketDataService.backfillMissingCandlesBeforeInsert(
                    symbol,
                    interval,
                    latestBeforeInsert,
                    incomingMarketData.getEndTime().atZone(UTC).toInstant()
            );

            boolean exists = marketDataRepository.existsBySymbolAndIntervalAndStartTime(
                    symbol,
                    interval,
                    incomingMarketData.getStartTime()
            );

            if (!exists) {
                persistMarketData(interval, incomingMarketData);
                processPostPersist(interval, incomingMarketData, incomingMarketData.getStartTime());
            } else {
                log.debug("Candle startTime already exists, skipping strategy execution | interval={} startTime={}",
                        interval, incomingMarketData.getStartTime());
            }

        } catch (Exception e) {
            log.error("Failed to process websocket message: {}", message, e);
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

    /**
     * Builds the combined-stream URL for the configured symbol across all
     * subscribed intervals — e.g. {@code wss://.../stream?streams=btcusdt@kline_5m/btcusdt@kline_15m/...}.
     * Lower-cased per Binance's stream-name contract; intervals iterated in
     * declaration order so the URL is deterministic for log diffing.
     */
    private String buildBinanceWsUrl() {
        String prefix = symbol.toLowerCase();
        String streams = SUBSCRIBED_INTERVALS.stream()
                .map(i -> prefix + "@kline_" + i)
                .collect(Collectors.joining("/"));
        return WS_BASE + "/stream?streams=" + streams;
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
        marketData.setSymbol(symbol);
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

        log.info("Inserted candle | symbol={} interval={} endTime={} close={}", symbol, interval, marketData.getEndTime(), marketData.getClosePrice());
    }

    private void processPostPersist(String interval, MarketData marketData, LocalDateTime startTime) {
        FeatureStore featureStore = null;

        if (requiresFeatureComputation(interval)) {
            featureStore = technicalIndicatorService.computeIndicatorsAndStore(symbol, interval, startTime);
        }

        if (featureStore != null) {
            List<AccountStrategy> activeStrategies = accountStrategyRepository
                    .findByEnabledTrueAndIntervalName(interval);

            Map<UUID, Account> activeAccountMap = accountRepository.findByIsActive("1")
                    .stream()
                    .collect(Collectors.toMap(Account::getAccountId, a -> a));

            Map<UUID, List<AccountStrategy>> byAccount = activeStrategies.stream()
                    .collect(Collectors.groupingBy(AccountStrategy::getAccountId));

            for (Map.Entry<UUID, List<AccountStrategy>> entry : byAccount.entrySet()) {
                UUID accountId = entry.getKey();
                List<AccountStrategy> strategies = entry.getValue();

                Account account = activeAccountMap.get(accountId);
                if (account == null) {
                    continue;
                }

                try {
                    if (strategies.size() == 1) {
                        liveTradingCoordinatorService.process(account, strategies.getFirst(), symbol, interval, marketData, featureStore);
                    } else {
                        liveOrchestratorCoordinatorService.process(account, strategies, symbol, interval, marketData, featureStore);
                    }

                } catch (Exception e) {
                    log.error(
                            "Live strategy execution failed | accountId={} symbol={} interval={}",
                            accountId, symbol, interval, e
                    );
                }
            }
        }


    }

    private boolean requiresFeatureComputation(String interval) {
        return ACCEPTED_INTERVALS.contains(interval);
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

