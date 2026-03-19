package id.co.blackheart.service.technicalindicator;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.util.MapperUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.num.Num;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;


import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@Slf4j
@AllArgsConstructor
public class TechnicalIndicatorService {

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final MapperUtil mapperUtil;


    public FeatureStore computeIndicatorsAndStoreByStartTime(String symbol, String interval, LocalDateTime startTime) {
        boolean exists = featureStoreRepository.existsBySymbolAndIntervalAndStartTime(symbol, interval, startTime);
        if (exists) {
            log.debug("Feature already exists, skip computation | symbol={} interval={} startTime={}",
                    symbol, interval, startTime);
            return null;
        }

        return computeIndicatorsAndStore(symbol, interval,startTime);
    }


    public FeatureStore computeIndicatorsAndStore(String symbol, String interval, LocalDateTime startTime) {




        List<MarketData> historicalData = marketDataRepository.findLast300BySymbolAndIntervalAndTime(symbol, interval, startTime);

        if (historicalData == null || historicalData.size() < 250) {
            log.warn("Not enough historical data to compute indicators for {} {}", symbol, interval);
            return null;
        }

        // Make sure the data is sorted ascending by start time
        historicalData.sort(Comparator.comparing(MarketData::getStartTime));

        MarketData latestMarketData = historicalData.getLast();
        BigDecimal price = latestMarketData.getClosePrice();

        BarSeries series = convertToBarSeries(historicalData,interval);
        int endIndex = series.getEndIndex();

        Optional<FeatureStore> existingFeature = featureStoreRepository.findBySymbolAndIntervalAndStartTime(symbol, interval, latestMarketData.getStartTime());

        if (existingFeature.isPresent()) {
            log.debug("Feature already exists. symbol={} interval={} startTime={}",symbol, interval, latestMarketData.getStartTime());
            return existingFeature.get();
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        FeatureStore featureData = new FeatureStore();
        featureData.setIdMarketData(latestMarketData.getId());
        featureData.setSymbol(symbol);
        featureData.setInterval(interval);
        featureData.setStartTime(latestMarketData.getStartTime());
        featureData.setEndTime(latestMarketData.getEndTime());
        featureData.setPrice(price);

        // Trend
        EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
        EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
        EMAIndicator ema200 = new EMAIndicator(closePrice, 200);

        featureData.setEma20(toBigDecimal(ema20.getValue(endIndex)));
        featureData.setEma50(toBigDecimal(ema50.getValue(endIndex)));
        featureData.setEma200(toBigDecimal(ema200.getValue(endIndex)));

        if (endIndex > 0) {
            featureData.setEma50Slope(
                    toBigDecimal(ema50.getValue(endIndex).minus(ema50.getValue(endIndex - 1)))
            );
            featureData.setEma200Slope(
                    toBigDecimal(ema200.getValue(endIndex).minus(ema200.getValue(endIndex - 1)))
            );
        } else {
            featureData.setEma50Slope(BigDecimal.ZERO);
            featureData.setEma200Slope(BigDecimal.ZERO);
        }

        // Trend strength
        ADXIndicator adx = new ADXIndicator(series, 14);
        PlusDIIndicator plusDI = new PlusDIIndicator(series, 14);
        MinusDIIndicator minusDI = new MinusDIIndicator(series, 14);

        featureData.setAdx(toBigDecimal(adx.getValue(endIndex)));
        featureData.setPlusDI(toBigDecimal(plusDI.getValue(endIndex)));
        featureData.setMinusDI(toBigDecimal(minusDI.getValue(endIndex)));
        featureData.setEfficiencyRatio20(calculateEfficiencyRatio(historicalData, 20));

        // Volatility
        ATRIndicator atr = new ATRIndicator(series, 14);
        BigDecimal atrValue = toBigDecimal(atr.getValue(endIndex));

        featureData.setAtr(atrValue);
        featureData.setAtrPct(
                price.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : atrValue.divide(price, 8, RoundingMode.HALF_UP)
        );

        // Momentum
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);

        BigDecimal macdValue = toBigDecimal(macd.getValue(endIndex));
        BigDecimal macdSignalValue = toBigDecimal(macdSignal.getValue(endIndex));

        featureData.setMacd(macdValue);
        featureData.setMacdSignal(macdSignalValue);
        featureData.setMacdHistogram(macdValue.subtract(macdSignalValue));
        featureData.setRsi(toBigDecimal(new RSIIndicator(closePrice, 14).getValue(endIndex)));

        // Structure / breakout
        featureData.setHighestHigh20(calculateHighestHigh(historicalData, 20));
        featureData.setLowestLow20(calculateLowestLow(historicalData, 20));
        featureData.setDonchianUpper20(featureData.getHighestHigh20());
        featureData.setDonchianLower20(featureData.getLowestLow20());
        featureData.setDonchianMid20(
                featureData.getDonchianUpper20()
                        .add(featureData.getDonchianLower20())
                        .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP)
        );

        // Candle quality
        BigDecimal open = latestMarketData.getOpenPrice();
        BigDecimal high = latestMarketData.getHighPrice();
        BigDecimal low = latestMarketData.getLowPrice();
        BigDecimal close = latestMarketData.getClosePrice();

        BigDecimal bodySize = close.subtract(open).abs();
        BigDecimal candleRange = high.subtract(low);

        featureData.setBodySize(bodySize);
        featureData.setCandleRange(candleRange);
        featureData.setBodyToRangeRatio(
                candleRange.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : bodySize.divide(candleRange, 8, RoundingMode.HALF_UP)
        );
        featureData.setCloseLocationValue(
                candleRange.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : close.subtract(low).divide(candleRange, 8, RoundingMode.HALF_UP)
        );

        featureData.setRelativeVolume20(calculateRelativeVolume(historicalData, 20));

        // Regime summary
        Integer trendScore = calculateTrendScore(featureData, price);
        featureData.setTrendScore(trendScore);
        featureData.setTrendRegime(resolveTrendRegime(trendScore));
        featureData.setVolatilityRegime(resolveVolatilityRegime(featureData.getAtrPct()));
        featureData.setIsBearishBreakout(isBearishBreakout(featureData, price));
        featureData.setIsBullishBreakout(isBullishBreakout(featureData, price));
        featureData.setIsBullishPullback(isBullishPullback(featureData, price));
        featureData.setIsBearishPullback(isBearishPullback(featureData, price));
        featureData.setEntryBias(resolveEntryBias(featureData));
        return  featureStoreRepository.save(featureData);
    }

    private boolean isBullishPullback(FeatureStore feature, BigDecimal price) {
        if (feature == null || price == null) {
            log.debug("Bullish pullback check failed: feature or price is null");
            return false;
        }

        if (feature.getEma20() == null || feature.getEma50() == null) {
            log.debug("Bullish pullback check failed: ema20 or ema50 is null");
            return false;
        }

        BigDecimal ema20 = feature.getEma20();
        BigDecimal ema50 = feature.getEma50();

        // Tolerance: 0.2% from EMA20
        BigDecimal tolerance = ema20.multiply(new BigDecimal("0.002"));

        boolean bullishStructure = "BULL".equalsIgnoreCase(feature.getTrendRegime())
                && ema20.compareTo(ema50) > 0;

        boolean nearEma20 = price.compareTo(ema20.subtract(tolerance)) >= 0
                && price.compareTo(ema20.add(tolerance)) <= 0;

        boolean stillAboveEma50 = price.compareTo(ema50) >= 0;

        boolean acceptableCandle = feature.getCloseLocationValue() == null
                || feature.getCloseLocationValue().compareTo(new BigDecimal("0.4")) >= 0;

        boolean result = bullishStructure
                && nearEma20
                && stillAboveEma50
                && acceptableCandle;

        log.debug(
                "Bullish pullback analysis: price={} ema20={} ema50={} bullishStructure={} nearEma20={} stillAboveEma50={} acceptableCandle={} final={}",
                price,
                ema20,
                ema50,
                bullishStructure,
                nearEma20,
                stillAboveEma50,
                acceptableCandle,
                result
        );

        return result;
    }

    private boolean isBearishPullback(FeatureStore feature, BigDecimal price) {
        if (feature == null || price == null) {
            log.debug("Bearish pullback check failed: feature or price is null");
            return false;
        }

        if (feature.getEma20() == null || feature.getEma50() == null) {
            log.debug("Bearish pullback check failed: ema20 or ema50 is null");
            return false;
        }

        BigDecimal ema20 = feature.getEma20();
        BigDecimal ema50 = feature.getEma50();

        // Tolerance: 0.2% from EMA20
        BigDecimal tolerance = ema20.multiply(new BigDecimal("0.002"));

        boolean bearishStructure = "BEAR".equalsIgnoreCase(feature.getTrendRegime())
                && ema20.compareTo(ema50) < 0;

        boolean nearEma20 = price.compareTo(ema20.subtract(tolerance)) >= 0
                && price.compareTo(ema20.add(tolerance)) <= 0;

        boolean stillBelowEma50 = price.compareTo(ema50) <= 0;

        boolean acceptableCandle = feature.getCloseLocationValue() == null
                || feature.getCloseLocationValue().compareTo(new BigDecimal("0.6")) <= 0;

        boolean result = bearishStructure
                && nearEma20
                && stillBelowEma50
                && acceptableCandle;

        log.debug(
                "Bearish pullback analysis: price={} ema20={} ema50={} bearishStructure={} nearEma20={} stillBelowEma50={} acceptableCandle={} final={}",
                price,
                ema20,
                ema50,
                bearishStructure,
                nearEma20,
                stillBelowEma50,
                acceptableCandle,
                result
        );

        return result;
    }

    private boolean isBullishBreakout(FeatureStore feature, BigDecimal price) {
        if (feature.getDonchianUpper20() == null || price == null) {
            log.debug("Bullish breakout check failed: Donchian upper or price is null");
            return false;
        }

        BigDecimal buffer = feature.getDonchianUpper20().multiply(new BigDecimal("0.001"));

        boolean upperBreakout = price.compareTo(feature.getDonchianUpper20()) >= 0;
        boolean nearUpperBreakout = price.compareTo(feature.getDonchianUpper20().subtract(buffer)) >= 0;

        boolean result = upperBreakout || nearUpperBreakout;

        log.debug("Bullish breakout analysis: price={} upper={} upperBreakout={} nearUpperBreakout={} final={}",
                price, feature.getDonchianUpper20(), upperBreakout, nearUpperBreakout, result);

        return result;
    }

    private boolean isBearishBreakout(FeatureStore feature, BigDecimal price) {
        if (feature.getDonchianLower20() == null || price == null) {
            log.debug("Bearish breakout check failed: Donchian lower or price is null");
            return false;
        }

        BigDecimal buffer = feature.getDonchianLower20().multiply(new BigDecimal("0.001"));

        boolean lowerBreakout = price.compareTo(feature.getDonchianLower20()) <= 0;
        boolean nearLowerBreakout = price.compareTo(feature.getDonchianLower20().add(buffer)) <= 0;

        boolean result = lowerBreakout || nearLowerBreakout;

        log.debug("Bearish breakout analysis: price={} lower={} lowerBreakout={} nearLowerBreakout={} final={}",
                price, feature.getDonchianLower20(), lowerBreakout, nearLowerBreakout, result);

        return result;
    }

    private String resolveEntryBias(FeatureStore feature) {
        if ("BULL".equals(feature.getTrendRegime())) {
            return "LONG";
        }
        if ("BEAR".equals(feature.getTrendRegime())) {
            return "SHORT";
        }
        return "NONE";
    }

    private String resolveTrendRegime(Integer trendScore) {
        if (trendScore == null) {
            return "NEUTRAL";
        }
        if (trendScore >= 4) {
            return "BULL";
        }
        if (trendScore <= 1) {
            return "BEAR";
        }
        return "NEUTRAL";
    }

    private String resolveVolatilityRegime(BigDecimal atrPct) {
        if (atrPct == null) {
            return "NORMAL";
        }

        if (atrPct.compareTo(new BigDecimal("0.04")) >= 0) {
            return "HIGH";
        }
        if (atrPct.compareTo(new BigDecimal("0.015")) <= 0) {
            return "LOW";
        }
        return "NORMAL";
    }

    private Integer calculateTrendScore(FeatureStore feature, BigDecimal price) {
        int score = 0;

        if (feature.getEma20() != null && price.compareTo(feature.getEma20()) > 0) score++;
        if (feature.getEma50() != null && price.compareTo(feature.getEma50()) > 0) score++;
        if (feature.getEma200() != null && price.compareTo(feature.getEma200()) > 0) score++;
        if (feature.getEma50() != null && feature.getEma200() != null
                && feature.getEma50().compareTo(feature.getEma200()) > 0) score++;
        if (feature.getAdx() != null && feature.getAdx().compareTo(new BigDecimal("20")) >= 0) score++;

        return score;
    }

    private BigDecimal calculateRelativeVolume(List<MarketData> data, int period) {
        if (data.size() < period) {
            return BigDecimal.ZERO;
        }

        List<MarketData> recent = data.subList(Math.max(0, data.size() - period), data.size());
        BigDecimal avgVolume = recent.stream()
                .map(MarketData::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(recent.size()), 8, RoundingMode.HALF_UP);

        BigDecimal currentVolume = data.getLast().getVolume();

        if (avgVolume.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return currentVolume.divide(avgVolume, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateHighestHigh(List<MarketData> data, int period) {
        return data.stream()
                .skip(Math.max(0, data.size() - period))
                .map(MarketData::getHighPrice)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal calculateLowestLow(List<MarketData> data, int period) {
        return data.stream()
                .skip(Math.max(0, data.size() - period))
                .map(MarketData::getLowPrice)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal calculateEfficiencyRatio(List<MarketData> data, int period) {
        if (data.size() < period + 1) {
            return BigDecimal.ZERO;
        }

        int end = data.size() - 1;
        int start = end - period;

        BigDecimal direction = data.get(end).getClosePrice()
                .subtract(data.get(start).getClosePrice())
                .abs();

        BigDecimal volatility = BigDecimal.ZERO;
        for (int i = start + 1; i <= end; i++) {
            BigDecimal currentClose = data.get(i).getClosePrice();
            BigDecimal previousClose = data.get(i - 1).getClosePrice();
            volatility = volatility.add(currentClose.subtract(previousClose).abs());
        }

        if (volatility.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return direction.divide(volatility, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal toBigDecimal(Num value) {
        return new BigDecimal(value.toString()).setScale(8, RoundingMode.HALF_UP);
    }

    private BarSeries convertToBarSeries(List<MarketData> historicalData, String interval) {
        BarSeries series = new BaseBarSeries();

        Duration barDuration = Duration.ofMinutes(mapperUtil.getIntervalMinutes(interval));

        for (MarketData data : historicalData) {
            Instant barTimestamp = data.getEndTime().atZone(ZoneId.of("UTC")).toInstant();

            Num openPrice = series.numOf(data.getOpenPrice());
            Num highPrice = series.numOf(data.getHighPrice());
            Num lowPrice = series.numOf(data.getLowPrice());
            Num closePrice = series.numOf(data.getClosePrice());
            Num volume = series.numOf(data.getVolume());

            BaseBar bar = BaseBar.builder()
                    .timePeriod(barDuration)
                    .endTime(barTimestamp.atZone(ZoneId.of("UTC")))
                    .openPrice(openPrice)
                    .highPrice(highPrice)
                    .lowPrice(lowPrice)
                    .closePrice(closePrice)
                    .volume(volume)
                    .build();

            series.addBar(bar);
        }

        return series;
    }
}

