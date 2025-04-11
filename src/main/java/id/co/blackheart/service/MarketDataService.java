package id.co.blackheart.service;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.client.DeepLearningClientService;
import id.co.blackheart.dto.response.PredictionResponse;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.MarketDataRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@Slf4j
@AllArgsConstructor
public class MarketDataService {
    MarketDataRepository marketDataRepository;
    DeepLearningClientService deepLearningClientService;
    TechnicalIndicatorService technicalIndicatorService;


    public boolean checkAndFetchMissingCandles(String symbol, Instant latestKlineEndTime, String interval) {

        MarketData latestMarketData = marketDataRepository.findLatestBySymbol(symbol, interval);

        if (latestMarketData.getEndTime() != null) {
            Instant lastInsertedInstant = latestMarketData.getEndTime().atZone(ZoneId.of("UTC")).toInstant();

            // If last inserted candle is older than 15 minutes, fetch missing data
            if (Duration.between(lastInsertedInstant, latestKlineEndTime).toMinutes() >= 16) {
                log.warn("⚠ Missing candlestick detected! Fetching missing data for {}", symbol);
                fetchMissingCandles(symbol, lastInsertedInstant.toEpochMilli(), latestKlineEndTime.toEpochMilli());
                return true;
            }
        } else {
            log.warn("⚠ No historical candlesticks found! Fetching initial data...");
            fetchMissingCandles(symbol, latestKlineEndTime.minusSeconds(3600).toEpochMilli(), latestKlineEndTime.toEpochMilli());
            return true;
        }
        return false;
    }



    private void fetchMissingCandles(String symbol, long startTime, long endTime) {
        String url = String.format("https://api.binance.com/api/v3/klines?symbol=%s&interval=15m&limit=1000&startTime=%d&endTime=%d",
                symbol, startTime, endTime);

        try {
            RestTemplate restTemplate = new RestTemplate();
            ObjectMapper objectMapper = new ObjectMapper();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Object[]> klineData = objectMapper.readValue(response.getBody(), new TypeReference<List<Object[]>>() {});

                for (Object[] kline : klineData) {
                    MarketData marketData = new MarketData();
                    marketData.setSymbol(symbol);
                    marketData.setInterval("15m");
                    marketData.setStartTime(LocalDateTime.ofInstant(Instant.ofEpochMilli((Long) kline[0]), ZoneId.of("UTC")));
                    marketData.setEndTime(LocalDateTime.ofInstant(Instant.ofEpochMilli((Long) kline[6]), ZoneId.of("UTC")));
                    marketData.setOpenPrice(new BigDecimal(kline[1].toString()));
                    marketData.setClosePrice(new BigDecimal(kline[4].toString()));
                    marketData.setHighPrice(new BigDecimal(kline[2].toString()));
                    marketData.setLowPrice(new BigDecimal(kline[3].toString()));
                    marketData.setVolume(new BigDecimal(kline[5].toString()));
                    marketData.setTradeCount(((Number) kline[8]).longValue());
                    marketData.setTimestamp(Instant.ofEpochMilli((Long) kline[6]));

                    marketDataRepository.save(marketData);
                    PredictionResponse predictionResponse = deepLearningClientService.sendPredictionRequest();

                    technicalIndicatorService.computeIndicatorsAndStore("BTCUSDT", Instant.ofEpochSecond(System.currentTimeMillis()), predictionResponse);
                    log.info("✅ Inserted missing candlestick: {}", marketData);
                }
            }
        } catch (Exception e) {
            log.error("❌ Failed to fetch missing candlestick data from Binance API", e);
        }
    }

}
