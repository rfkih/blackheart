package id.co.blackheart.stream;


import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.TechnicalIndicatorService;
import id.co.blackheart.service.TradingService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
@AllArgsConstructor
public class BinanceWebSocketClient {

    private static final String BINANCE_WS_URL = "wss://stream.binance.com:9443/ws/btcusdt@kline_1m";
    private final MarketDataRepository marketDataRepository;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final TechnicalIndicatorService technicalIndicatorService;
    private final TradingService tradingService;


    public void connect() {
        executorService.submit(() -> {
            while (true) {
                try {
                    new ReactorNettyWebSocketClient()
                            .execute(URI.create(BINANCE_WS_URL), session ->
                                    session.receive()
                                            .map(webSocketMessage -> webSocketMessage.getPayloadAsText())
                                            .doOnNext(this::handleMessage)
                                            .then()
                            )
                            .retry()
                            .subscribe();

                    log.info("WebSocket connection established.");
                    break; // Exit retry loop on successful connection

                } catch (Exception e) {
                    log.error("WebSocket connection failed. Retrying in 5 seconds...", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
    }

    private void handleMessage(String message) {
        try {
            String symbol = "BTCUSDT";
            JSONObject json = new JSONObject(message);
            JSONObject kline = json.getJSONObject("k");
            FeatureStore featureStore = new FeatureStore();
            MarketData marketData = new MarketData();
            String action = "";

            Instant startTime = Instant.ofEpochMilli(kline.getLong("t"));  // Kline start time
            Instant endTime = Instant.ofEpochMilli(kline.getLong("T"));    // Kline end time
            Instant eventTime = Instant.ofEpochMilli(json.getLong("E"));  // Event timestamp

            // Extract numerical values
            double openPrice = kline.getBigDecimal("o").doubleValue();
            double closePrice = kline.getBigDecimal("c").doubleValue();
            double highPrice = kline.getBigDecimal("h").doubleValue();
            double lowPrice = kline.getBigDecimal("l").doubleValue();
            double volume = kline.getBigDecimal("v").doubleValue();
            long tradeCount = kline.getLong("n");

            boolean isFinal = kline.getBoolean("x");

            if (isFinal) {
                marketData.setSymbol(symbol);
                marketData.setInterval("1m");
                marketData.setStartTime(LocalDateTime.ofInstant(startTime, ZoneId.of("UTC")));
                marketData.setEndTime(LocalDateTime.ofInstant(endTime, ZoneId.of("UTC")));
                marketData.setOpenPrice(BigDecimal.valueOf(openPrice));
                marketData.setClosePrice(BigDecimal.valueOf(closePrice));
                marketData.setHighPrice(BigDecimal.valueOf(highPrice));
                marketData.setLowPrice(BigDecimal.valueOf(lowPrice));
                marketData.setVolume(BigDecimal.valueOf(volume));
                marketData.setTradeCount(tradeCount);
                marketDataRepository.save(marketData);
                log.info("Saved finalized candlestick: {}", marketData);

               featureStore = technicalIndicatorService.computeIndicatorsAndStore("BTCUSDT", eventTime);
               tradingService.trendFollwoingTradeAction(marketData,featureStore,BigDecimal.valueOf(0.02),BigDecimal.valueOf(2L),1L,"BTCUSDT");
            }
        } catch (Exception e) {
            log.error("Error parsing WebSocket message: {}", message, e);
        }
    }
}