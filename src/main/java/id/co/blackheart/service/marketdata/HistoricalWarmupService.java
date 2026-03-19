package id.co.blackheart.service.marketdata;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.technicalindicator.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalWarmupService {

    private static final String BINANCE_BASE_URL = "https://api.binance.com";
    private static final String BINANCE_KLINES_PATH = "/api/v3/klines";
    private static final int BINANCE_MAX_LIMIT = 1000;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final TechnicalIndicatorService technicalIndicatorService;

    private final WebClient webClient = WebClient.builder()
            .baseUrl(BINANCE_BASE_URL)
            .build();

    public void backfillLastCandlesAndFeatures(String symbol, String interval) {
        backfillLastNCandlesAndRepairMissingFeatures(symbol, interval, 5000, 300);
    }

    public void backfillLastNCandlesAndRepairMissingFeatures( String symbol, String interval, int targetCandles,int warmupCandles) {
        validateInputs(symbol, interval, targetCandles, warmupCandles);

        int totalCandlesToFetch = targetCandles + warmupCandles;

        MarketDataBackfillStats marketStats = backfillMissingMarketData(symbol, interval, totalCandlesToFetch);
        FeatureRepairStats featureStats = repairMissingFeatureStore(symbol, interval, targetCandles, warmupCandles);

        log.info("""
                        Historical warmup finished
                        symbol={}
                        interval={}
                        targetCandles={}
                        warmupCandles={}
                        fetchedCandles={}
                        insertedMarketData={}
                        skippedMarketData={}
                        insertedFeatures={}
                        skippedFeatures={}
                        targetRangeSize={}
                        """,
                symbol,
                interval,
                targetCandles,
                warmupCandles,
                marketStats.fetchedCandles(),
                marketStats.insertedMarketData(),
                marketStats.skippedMarketData(),
                featureStats.insertedFeatures(),
                featureStats.skippedFeatures(),
                featureStats.targetRangeSize()
        );
    }

    private void validateInputs(String symbol, String interval, int targetCandles, int warmupCandles) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (interval == null || interval.isBlank()) {
            throw new IllegalArgumentException("interval cannot be blank");
        }
        if (targetCandles <= 0) {
            throw new IllegalArgumentException("targetCandles must be greater than zero");
        }
        if (warmupCandles < 0) {
            throw new IllegalArgumentException("warmupCandles cannot be negative");
        }
    }

    private MarketDataBackfillStats backfillMissingMarketData(String symbol, String interval, int totalCandlesToFetch) {
        int insertedMarketData = 0;
        int skippedMarketData = 0;

        List<JSONArray> allCandles = fetchLastNCandlesBackward(symbol, interval, totalCandlesToFetch);

        for (JSONArray candle : allCandles) {
            LocalDateTime startTime = extractStartTime(candle);
            LocalDateTime endTime = extractEndTime(candle);

            boolean exists = marketDataRepository.existsBySymbolAndIntervalAndStartTime(symbol, interval, startTime);

            if (exists) {
                skippedMarketData++;
                continue;
            }

            MarketData marketData = createMarketDataFromCandle(candle, symbol, interval, startTime, endTime);
            marketDataRepository.save(marketData);
            insertedMarketData++;

            log.info("Inserted market_data | symbol={} interval={} startTime={}",
                    symbol, interval, startTime);
        }

        return new MarketDataBackfillStats(
                allCandles.size(),
                insertedMarketData,
                skippedMarketData
        );
    }

    private FeatureRepairStats repairMissingFeatureStore(
            String symbol,
            String interval,
            int targetCandles,
            int warmupCandles
    ) {
        int totalNeeded = targetCandles + warmupCandles;

        List<MarketData> latestCandles = marketDataRepository.findLatestCandles(symbol, interval, totalNeeded);

        if (latestCandles == null || latestCandles.size() <= warmupCandles) {
            throw new IllegalStateException("Not enough market_data found to repair feature_store");
        }

        latestCandles.sort(Comparator.comparing(MarketData::getStartTime));

        List<MarketData> targetCandlesOnly = latestCandles.subList(warmupCandles, latestCandles.size());

        LocalDateTime targetStartTime = targetCandlesOnly.getFirst().getStartTime();
        LocalDateTime targetEndTime = targetCandlesOnly.getLast().getStartTime();

        Set<LocalDateTime> existingFeatureStartTimes = featureStoreRepository
                .findExistingStartTimesInRange(symbol, interval, targetStartTime, targetEndTime)
                .stream()
                .map(java.sql.Timestamp::toLocalDateTime)
                .collect(java.util.stream.Collectors.toSet());

        int insertedFeatures = 0;
        int skippedFeatures = 0;

        for (MarketData targetCandle : targetCandlesOnly) {
            LocalDateTime startTime = targetCandle.getStartTime();

            if (existingFeatureStartTimes.contains(startTime)) {
                skippedFeatures++;
                continue;
            }

            FeatureStore featureStore = technicalIndicatorService.computeIndicatorsAndStoreByStartTime(
                    symbol,
                    interval,
                    startTime
            );

            if (featureStore != null) {
                insertedFeatures++;
                log.info("Inserted feature_store | symbol={} interval={} startTime={}",
                        symbol, interval, startTime);
            } else {
                log.warn("Feature computation skipped/failed | symbol={} interval={} startTime={}",
                        symbol, interval, startTime);
            }
        }

        return new FeatureRepairStats(
                insertedFeatures,
                skippedFeatures,
                targetCandlesOnly.size()
        );
    }

    private List<JSONArray> fetchLastNCandlesBackward(String symbol, String interval, int totalCandles) {
        int remaining = totalCandles;
        Long endTimeMs = null;
        List<JSONArray> allCandles = new ArrayList<>();

        while (remaining > 0) {
            int requestLimit = Math.min(remaining, BINANCE_MAX_LIMIT);
            JSONArray candles = fetchKlinesBackward(symbol, interval, requestLimit, endTimeMs);

            if (candles.isEmpty()) {
                log.warn("No more candles returned from Binance | symbol={} interval={} remaining={}",
                        symbol, interval, remaining);
                break;
            }

            for (int i = 0; i < candles.length(); i++) {
                allCandles.add(candles.getJSONArray(i));
            }

            remaining -= candles.length();

            long firstOpenTimeMs = candles.getJSONArray(0).getLong(0);
            endTimeMs = firstOpenTimeMs - 1L;

            if (candles.length() < requestLimit) {
                log.info("Binance returned fewer candles than requested. Stopping pagination early.");
                break;
            }
        }

        allCandles.sort(Comparator.comparingLong(c -> c.getLong(0)));

        return deduplicateCandlesByOpenTime(allCandles);
    }

    private List<JSONArray> deduplicateCandlesByOpenTime(List<JSONArray> candles) {
        List<JSONArray> result = new ArrayList<>();
        Set<Long> seenOpenTimes = new HashSet<>();

        for (JSONArray candle : candles) {
            long openTime = candle.getLong(0);
            if (seenOpenTimes.add(openTime)) {
                result.add(candle);
            }
        }

        return result;
    }

    private JSONArray fetchKlinesBackward(String symbol, String interval, int limit, Long endTimeMs) {
        String response = webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path(BINANCE_KLINES_PATH)
                            .queryParam("symbol", symbol)
                            .queryParam("interval", interval)
                            .queryParam("limit", limit);

                    if (endTimeMs != null) {
                        builder.queryParam("endTime", endTimeMs);
                    }

                    return builder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null || response.isBlank()) {
            return new JSONArray();
        }

        return new JSONArray(response);
    }

    private LocalDateTime extractStartTime(JSONArray candle) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getLong(0)), UTC
        );
    }

    private LocalDateTime extractEndTime(JSONArray candle) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getLong(6)), UTC
        );
    }

    private MarketData createMarketDataFromCandle(
            JSONArray candle,
            String symbol,
            String interval,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        MarketData marketData = new MarketData();
        marketData.setSymbol(symbol);
        marketData.setInterval(interval);
        marketData.setStartTime(startTime);
        marketData.setEndTime(endTime);
        marketData.setOpenPrice(new BigDecimal(candle.getString(1)));
        marketData.setHighPrice(new BigDecimal(candle.getString(2)));
        marketData.setLowPrice(new BigDecimal(candle.getString(3)));
        marketData.setClosePrice(new BigDecimal(candle.getString(4)));
        marketData.setVolume(new BigDecimal(candle.getString(5)));
        marketData.setQuoteAssetVolume(new BigDecimal(candle.getString(7)));
        marketData.setTradeCount(candle.getLong(8));
        marketData.setTakerBuyBaseVolume(new BigDecimal(candle.getString(9)));
        marketData.setTakerBuyQuoteVolume(new BigDecimal(candle.getString(10)));
        marketData.setCreatedTime(Instant.now());
        return marketData;
    }

    private record MarketDataBackfillStats(
            int fetchedCandles,
            int insertedMarketData,
            int skippedMarketData
    ) {
    }

    private record FeatureRepairStats(
            int insertedFeatures,
            int skippedFeatures,
            int targetRangeSize
    ) {
    }
}