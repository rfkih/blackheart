package id.co.blackheart.service.marketdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.technicalindicator.TechnicalIndicatorService;
import id.co.blackheart.util.MapperUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class MarketDataService {

    private static final long INITIAL_FETCH_CANDLE_COUNT = 300L;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final MarketDataRepository marketDataRepository;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final MapperUtil mapperUtil;
    private final RestTemplate binanceRestTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public MarketDataService(
            MarketDataRepository marketDataRepository,
            TechnicalIndicatorService technicalIndicatorService,
            MapperUtil mapperUtil,
            @Qualifier("binanceRestTemplate") RestTemplate binanceRestTemplate,
            ObjectMapper objectMapper
    ) {
        this.marketDataRepository = marketDataRepository;
        this.technicalIndicatorService = technicalIndicatorService;
        this.mapperUtil = mapperUtil;
        this.binanceRestTemplate = binanceRestTemplate;
        this.objectMapper = objectMapper;
    }

    public void backfillMissingCandlesBeforeInsert(
            String symbol,
            String interval,
            MarketData latestBeforeInsert,
            Instant incomingCandleEndTime
    ) {
        long intervalMinutes = mapperUtil.getIntervalMinutes(interval);
        Duration intervalDuration = Duration.ofMinutes(intervalMinutes);

        // If DB is empty, bootstrap the latest 300 candles.
        if (latestBeforeInsert == null || latestBeforeInsert.getEndTime() == null) {
            log.warn("No historical candlesticks found for symbol={} interval={}. Fetching latest {} candles before insert...",
                    symbol, interval, INITIAL_FETCH_CANDLE_COUNT);

            Instant fromTime = incomingCandleEndTime.minus(Duration.ofMinutes(intervalMinutes * INITIAL_FETCH_CANDLE_COUNT));

            fetchMissingCandles(symbol,fromTime.toEpochMilli(),incomingCandleEndTime.toEpochMilli(),interval);
            return;
        }

        Instant latestDbEndTime = latestBeforeInsert.getEndTime().atZone(UTC).toInstant();

        // Duplicate or old candle -> no backfill needed
        if (!incomingCandleEndTime.isAfter(latestDbEndTime)) {
            return;
        }

        Instant expectedNextEndTime = latestDbEndTime.plus(intervalDuration);

        // Continuous sequence -> no gap
        if (!incomingCandleEndTime.isAfter(expectedNextEndTime)) {
            return;
        }

        // Gap detected. Backfill only the most recent 300 candles window.
        Instant maxLookbackStart = incomingCandleEndTime.minus(Duration.ofMinutes(intervalMinutes * INITIAL_FETCH_CANDLE_COUNT));
        Instant fetchStart = latestDbEndTime.isAfter(maxLookbackStart) ? latestDbEndTime : maxLookbackStart;

        log.warn("Gap detected before insert. symbol={} interval={} latestDbEndTime={} incomingEndTime={} fetchStart={}",
                symbol, interval, latestDbEndTime, incomingCandleEndTime, fetchStart);

        fetchMissingCandles(symbol,fetchStart.toEpochMilli(),incomingCandleEndTime.toEpochMilli(),interval);
    }



    private void fetchMissingCandles(String symbol, long startTime, long endTime, String interval) {
        String url = String.format(
                "https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=1000&startTime=%d&endTime=%d",
                symbol, interval, startTime, endTime
        );

        try {
            ResponseEntity<String> response = binanceRestTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Failed to fetch missing candles. symbol={} interval={} status={}",
                        symbol, interval, response.getStatusCode());
                return;
            }

            List<Object[]> klineData = objectMapper.readValue(
                    response.getBody(),
                    new TypeReference<List<Object[]>>() {}
            );

            if (klineData.isEmpty()) {
                log.info("No missing candles returned from Binance. symbol={} interval={}", symbol, interval);
                return;
            }

            int insertedCount = 0;

            for (Object[] kline : klineData) {
                MarketData marketData = mapKlineToMarketData(symbol, interval, kline);

                boolean exists = marketDataRepository.existsBySymbolAndIntervalAndStartTime(
                        symbol,
                        interval,
                        marketData.getStartTime()
                );

                if (exists) {
                    continue;
                }

                marketDataRepository.save(marketData);
                insertedCount++;

                log.info("✅ Inserted missing candlestick. symbol={} interval={} startTime={} endTime={}",
                        symbol, interval, marketData.getStartTime(), marketData.getEndTime());

                if (shouldComputeIndicators(interval)) {
                    technicalIndicatorService.computeIndicatorsAndStore(symbol, interval, marketData.getStartTime());
                }
            }



            log.info("Missing candle fetch completed. symbol={} interval={} inserted={}",symbol, interval, insertedCount);

        } catch (Exception e) {
            log.error("❌ Failed to fetch missing candlestick data from Binance API. symbol={} interval={}",
                    symbol, interval, e);
        }
    }

    private boolean shouldComputeIndicators(String interval) {
        return "15m".equals(interval) || "1h".equals(interval) || "4h".equals(interval);
    }

    private MarketData mapKlineToMarketData(String symbol, String interval, Object[] kline) {
        MarketData marketData = new MarketData();

        marketData.setSymbol(symbol);
        marketData.setInterval(interval);
        marketData.setStartTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(((Number) kline[0]).longValue()),
                UTC
        ));
        marketData.setEndTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(((Number) kline[6]).longValue()),
                UTC
        ));
        marketData.setOpenPrice(new BigDecimal(kline[1].toString()));
        marketData.setHighPrice(new BigDecimal(kline[2].toString()));
        marketData.setLowPrice(new BigDecimal(kline[3].toString()));
        marketData.setClosePrice(new BigDecimal(kline[4].toString()));
        marketData.setVolume(new BigDecimal(kline[5].toString()));
        marketData.setQuoteAssetVolume(new BigDecimal(kline[7].toString()));
        marketData.setTradeCount(((Number) kline[8]).longValue());
        marketData.setTakerBuyBaseVolume(new BigDecimal(kline[9].toString()));
        marketData.setTakerBuyQuoteVolume(new BigDecimal(kline[10].toString()));
        marketData.setCreatedTime(Instant.now());

        return marketData;
    }
}