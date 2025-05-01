package id.co.blackheart.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.client.DeepLearningClientService;
import id.co.blackheart.dto.response.PredictionResponse;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.repository.UsersRepository;
import id.co.blackheart.service.MarketDataService;
import id.co.blackheart.service.TechnicalIndicatorService;
import id.co.blackheart.service.TradingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceWebSocketClient {

    private static final String BINANCE_WS_URL = "wss://stream.binance.com:9443/ws/btcusdt@kline_15m";
    private static final String INTERVAL = "15m";

    private final DeepLearningClientService deepLearningClientService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final TradingService tradingService;
    private final MarketDataRepository marketDataRepository;
    private final MarketDataService marketDataService;
    private final UsersRepository usersRepository;

    private final ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
    private volatile Instant lastMessageTime = Instant.now();
    private final Duration TIMEOUT_DURATION = Duration.ofSeconds(30);
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();

    private Disposable subscription;

    @PostConstruct
    public void start() {
        connect();
        startWatchdog();
    }

    public void connect() {
        log.info("Connecting to Binance WebSocket...");

        subscription = client
                .execute(URI.create(BINANCE_WS_URL), session ->
                        session.receive()
                                .map(message -> {
                                    lastMessageTime = Instant.now();
                                    return message.getPayloadAsText();
                                })
                                .flatMap(this::handleMessageReactive)
                                .onErrorContinue((ex, obj) -> log.error("WebSocket stream error", ex))
                                .then()
                )
                .retry()
                .subscribe(
                        null,
                        error -> {
                            log.error("WebSocket error", error);
                            reconnectWithDelay();
                        },
                        () -> {
                            log.warn("WebSocket stream closed. Reconnecting...");
                            reconnectWithDelay();
                        }
                );
    }

    private void reconnectWithDelay() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }

        Mono.delay(Duration.ofSeconds(5))
                .doOnNext(ignore -> connect())
                .subscribe();
    }

    private void startWatchdog() {
        watchdog.scheduleAtFixedRate(() -> {
            if (Duration.between(lastMessageTime, Instant.now()).compareTo(TIMEOUT_DURATION) > 0) {
                log.warn("WebSocket connection appears stale. Triggering reconnect...");
                reconnectWithDelay();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private Mono<Void> handleMessageReactive(String message) {
        try {
            JSONObject json = new JSONObject(message);
            JSONObject kline = json.getJSONObject("k");

            boolean isFinal = kline.getBoolean("x");
            log.info("Price update | Current: {}", kline.getString("c"));
            if (!isFinal) return Mono.empty();

            Instant startTime = Instant.ofEpochMilli(kline.getLong("t"));
            Instant endTime = Instant.ofEpochMilli(kline.getLong("T"));
            Instant eventTime = Instant.ofEpochMilli(json.getLong("E"));

            MarketData marketData = new MarketData();
            marketData.setSymbol("BTCUSDT");
            marketData.setInterval(INTERVAL);
            marketData.setStartTime(LocalDateTime.ofInstant(startTime, ZoneId.of("UTC")));
            marketData.setEndTime(LocalDateTime.ofInstant(endTime, ZoneId.of("UTC")));
            marketData.setOpenPrice(new BigDecimal(kline.getString("o")));
            marketData.setClosePrice(new BigDecimal(kline.getString("c")));
            marketData.setHighPrice(new BigDecimal(kline.getString("h")));
            marketData.setLowPrice(new BigDecimal(kline.getString("l")));
            marketData.setVolume(new BigDecimal(kline.getString("v")));
            marketData.setTradeCount(kline.getLong("n"));
            marketData.setTimestamp(eventTime);

            return deepLearningClientService.sendPredictionRequestReactive()
                    .publishOn(Schedulers.boundedElastic())
                    .doOnNext(prediction -> {
                        if (!marketDataService.checkAndFetchMissingCandles("BTCUSDT", endTime, INTERVAL)) {
                            marketDataRepository.save(marketData);
                            log.info("Saved: {}", marketData);
                        }

                        FeatureStore features = technicalIndicatorService.computeIndicatorsAndStore("BTCUSDT", eventTime, prediction);
                        List<Users> users = usersRepository.findByIsActiveAndExchange("1", "BNC");

                        for (Users user : users) {
                            try {
                                tradingService.cnnTransformerLongShortTradeAction(marketData, features, user, "BTCUSDT");
                            } catch (JsonProcessingException e) {
                                log.warn("Trading error", e);
                            }
                        }
                    })
                    .then();
        } catch (Exception e) {
            log.error("Message processing error: {}", message, e);
            return Mono.empty();
        }
    }
}

