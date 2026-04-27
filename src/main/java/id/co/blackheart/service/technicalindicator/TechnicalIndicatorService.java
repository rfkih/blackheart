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
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
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

        // ── VCB indicators ────────────────────────────────────────────────────

        StandardDeviationIndicator stdDev20 = new StandardDeviationIndicator(closePrice, 20);
        Num bbUpperValue = ema20.getValue(endIndex).plus(stdDev20.getValue(endIndex).multipliedBy(series.numOf(2)));
        Num bbLowerValue = ema20.getValue(endIndex).minus(stdDev20.getValue(endIndex).multipliedBy(series.numOf(2)));

        BigDecimal bbUpper = toBigDecimal(bbUpperValue);
        BigDecimal bbLower = toBigDecimal(bbLowerValue);
        BigDecimal ema20Value = toBigDecimal(ema20.getValue(endIndex));

        featureData.setBbUpperBand(bbUpper);
        featureData.setBbLowerBand(bbLower);
        featureData.setBbWidth(
                ema20Value.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : bbUpper.subtract(bbLower).divide(ema20Value, 8, RoundingMode.HALF_UP)
        );

        // 2. Keltner Channels (1.5 ATR multiplier — for squeeze detection)

        BigDecimal kcMultiplier = new BigDecimal("1.5");
        BigDecimal kcUpper = ema20Value.add(atrValue.multiply(kcMultiplier));
        BigDecimal kcLower = ema20Value.subtract(atrValue.multiply(kcMultiplier));

        featureData.setKcUpperBand(kcUpper);
        featureData.setKcLowerBand(kcLower);
        featureData.setKcWidth(
                ema20Value.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : kcUpper.subtract(kcLower).divide(ema20Value, 8, RoundingMode.HALF_UP)
        );

        // 3. ATR Ratio = current ATR / median ATR over last 20 periods
        featureData.setAtrRatio(calculateAtrRatio(historicalData, 14, 20));

        // 4. Signed ER20

        featureData.setSignedEr20(calculateSignedEfficiencyRatio(historicalData, 20));


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

    /**
     * ATR Ratio = current ATR(period) / median of last medianPeriod ATR values.
     *
     * We compute ATR for each of the last `medianPeriod` candles by using
     * a sliding window, then take the median. This is more robust than the
     * mean because it's not skewed by spike candles.
     *
     * < 0.90: volatility is compressed below its median → compression
     * > 1.50: volatility is expanding → breakout in progress
     *
     * @param data         full historical data list (sorted ascending)
     * @param atrPeriod    ATR calculation period (typically 14)
     * @param medianPeriod number of recent ATR samples for median (typically 20)
     */
    private BigDecimal calculateAtrRatio(List<MarketData> data, int atrPeriod, int medianPeriod) {
        if (data.size() < atrPeriod + medianPeriod) {
            return BigDecimal.ONE; // neutral default — not enough data
        }

        int end = data.size() - 1;

        // Calculate current ATR (last atrPeriod candles)
        BigDecimal currentAtr = calculateAtrSimple(data, end, atrPeriod);
        if (currentAtr.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE;

        // Calculate ATR values for the last medianPeriod windows
        List<BigDecimal> atrHistory = new java.util.ArrayList<>();
        for (int i = end - medianPeriod + 1; i <= end; i++) {
            if (i >= atrPeriod) {
                BigDecimal historicalAtr = calculateAtrSimple(data, i, atrPeriod);
                if (historicalAtr.compareTo(BigDecimal.ZERO) > 0) {
                    atrHistory.add(historicalAtr);
                }
            }
        }

        if (atrHistory.isEmpty()) return BigDecimal.ONE;

        // Median of ATR history
        java.util.Collections.sort(atrHistory);
        BigDecimal medianAtr;
        int size = atrHistory.size();
        if (size % 2 == 0) {
            medianAtr = atrHistory.get(size / 2 - 1)
                    .add(atrHistory.get(size / 2))
                    .divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
        } else {
            medianAtr = atrHistory.get(size / 2);
        }

        if (medianAtr.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE;

        return currentAtr.divide(medianAtr, 8, RoundingMode.HALF_UP);
    }

    /**
     * Simple ATR calculation for a single index point (not Ta4j dependent).
     * True Range = max(high-low, |high-prevClose|, |low-prevClose|)
     * ATR = average of TR over period candles ending at endIndex.
     */
    private BigDecimal calculateAtrSimple(List<MarketData> data, int endIndex, int period) {
        if (endIndex < 1 || endIndex - period + 1 < 1) return BigDecimal.ZERO;

        BigDecimal totalTr = BigDecimal.ZERO;
        int count = 0;

        for (int i = Math.max(1, endIndex - period + 1); i <= endIndex; i++) {
            MarketData curr = data.get(i);
            MarketData prev = data.get(i - 1);

            BigDecimal hl  = curr.getHighPrice().subtract(curr.getLowPrice()).abs();
            BigDecimal hpc = curr.getHighPrice().subtract(prev.getClosePrice()).abs();
            BigDecimal lpc = curr.getLowPrice().subtract(prev.getClosePrice()).abs();

            BigDecimal tr  = hl.max(hpc).max(lpc);
            totalTr = totalTr.add(tr);
            count++;
        }

        if (count == 0) return BigDecimal.ZERO;
        return totalTr.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }

    /**
     * General feature-backfill across a date range. Recomputes ALL indicator
     * columns the regular ingestion pipeline would produce — Bollinger
     * Bands, Keltner Channels, ATR ratio, signed efficiency ratio, EMAs,
     * RSI, MACD, ADX, Donchian, plus everything else
     * {@link #computeIndicatorsAndStore} computes — for every candle in
     * {@code [from, to]}.
     *
     * <p>Two modes:
     * <ul>
     *   <li>{@code recompute=false} (default) — only fills candles that
     *       have no FeatureStore row yet. Idempotent and safe to re-run
     *       on a partially-populated range.</li>
     *   <li>{@code recompute=true} — bulk-deletes every FeatureStore row
     *       in the range, then recomputes from scratch. Use when indicator
     *       code or parameters changed and existing rows are stale.</li>
     * </ul>
     *
     * <p>Returns the number of rows inserted.
     *
     * @param symbol     e.g. "BTCUSDT"
     * @param interval   e.g. "1h"
     * @param from       inclusive start of backfill window
     * @param to         inclusive end of backfill window
     * @param recompute  when true, delete existing rows in range before
     *                   inserting fresh; when false, only fill missing rows
     */
    @org.springframework.transaction.annotation.Transactional
    public int backfillFeaturesInRange(String symbol, String interval,
                                       LocalDateTime from, LocalDateTime to,
                                       boolean recompute) {
        List<MarketData> candlesInRange = marketDataRepository
                .findBySymbolIntervalAndRange(symbol, interval, from, to);

        if (candlesInRange == null || candlesInRange.isEmpty()) {
            log.info("Feature backfill: no market_data found for {}/{} [{} → {}]",
                    symbol, interval, from, to);
            return 0;
        }

        candlesInRange.sort(Comparator.comparing(MarketData::getStartTime));

        if (recompute) {
            int deleted = featureStoreRepository
                    .deleteBySymbolAndIntervalInRange(symbol, interval, from, to);
            log.info("Feature backfill (recompute): deleted {} existing rows for {}/{} [{} → {}]",
                    deleted, symbol, interval, from, to);
        }

        log.info("Feature backfill: {} candles to evaluate for {}/{} (recompute={})",
                candlesInRange.size(), symbol, interval, recompute);

        int inserted = 0;
        int skipped = 0;
        int failed = 0;

        for (MarketData candle : candlesInRange) {
            try {
                // Reuses the canonical compute path — same indicators the
                // live ingestion creates. Returns null if a row already
                // exists at this start time (idempotent on missing-only mode).
                FeatureStore fs = computeIndicatorsAndStoreByStartTime(
                        symbol, interval, candle.getStartTime());
                if (fs != null) {
                    inserted++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Feature backfill failed for {} {} startTime={}: {}",
                        symbol, interval, candle.getStartTime(), e.getMessage());
            }
        }

        log.info("Feature backfill complete: inserted={} skipped={} failed={} total={} for {}/{} [{} → {}]",
                inserted, skipped, failed, candlesInRange.size(),
                symbol, interval, from, to);
        return inserted;
    }

    /**
     * @deprecated Use {@link #backfillFeaturesInRange(String, String, LocalDateTime, LocalDateTime, boolean)}
     * instead. This method only patches VCB-specific columns (bbWidth, kcWidth,
     * atrRatio, signedEr20) on rows missing them, while the new general
     * backfill recomputes the full feature set. Kept for back-compat with
     * the legacy {@code /backfill-vcb} endpoint.
     */
    @Deprecated(since = "general-backfill")
    public int backfillVcbIndicators(String symbol, String interval,
                                     LocalDateTime from, LocalDateTime to) {
        List<FeatureStore> missing = featureStoreRepository
                .findMissingVcbIndicatorsInRange(symbol, interval, from, to);

        if (missing == null || missing.isEmpty()) {
            log.info("VCB backfill: no records need updating for {}/{} [{} → {}]",
                    symbol, interval, from, to);
            return 0;
        }

        log.info("VCB backfill: {} records to patch for {}/{}", missing.size(), symbol, interval);
        int updated = 0;

        for (FeatureStore fs : missing) {
            try {
                List<MarketData> historical = marketDataRepository
                        .findLast300BySymbolAndIntervalAndTime(symbol, interval, fs.getStartTime());

                if (historical == null || historical.size() < 50) {
                    log.warn("VCB backfill: not enough history for {} {} startTime={}",
                            symbol, interval, fs.getStartTime());
                    continue;
                }

                historical.sort(Comparator.comparing(MarketData::getStartTime));
                MarketData latest = historical.getLast();

                BarSeries series = convertToBarSeries(historical, interval);
                int endIndex = series.getEndIndex();

                ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
                EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
                ATRIndicator atr = new ATRIndicator(series, 14);

                Num ema20Val = ema20.getValue(endIndex);
                Num atrVal   = atr.getValue(endIndex);

                StandardDeviationIndicator stdDev20 = new StandardDeviationIndicator(closePrice, 20);
                Num bbUpperVal = ema20Val.plus(stdDev20.getValue(endIndex).multipliedBy(series.numOf(2)));
                Num bbLowerVal = ema20Val.minus(stdDev20.getValue(endIndex).multipliedBy(series.numOf(2)));

                BigDecimal bbUpper   = toBigDecimal(bbUpperVal);
                BigDecimal bbLower   = toBigDecimal(bbLowerVal);
                BigDecimal ema20Bd   = toBigDecimal(ema20Val);
                BigDecimal atrBd     = toBigDecimal(atrVal);

                fs.setBbUpperBand(bbUpper);
                fs.setBbLowerBand(bbLower);
                fs.setBbWidth(ema20Bd.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : bbUpper.subtract(bbLower).divide(ema20Bd, 8, RoundingMode.HALF_UP));

                BigDecimal kcMultiplier = new BigDecimal("1.5");
                BigDecimal kcUpper = ema20Bd.add(atrBd.multiply(kcMultiplier));
                BigDecimal kcLower = ema20Bd.subtract(atrBd.multiply(kcMultiplier));

                fs.setKcUpperBand(kcUpper);
                fs.setKcLowerBand(kcLower);
                fs.setKcWidth(ema20Bd.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : kcUpper.subtract(kcLower).divide(ema20Bd, 8, RoundingMode.HALF_UP));

                fs.setAtrRatio(calculateAtrRatio(historical, 14, 20));
                fs.setSignedEr20(calculateSignedEfficiencyRatio(historical, 20));

                featureStoreRepository.save(fs);
                updated++;

            } catch (Exception e) {
                log.error("VCB backfill failed for {} {} startTime={}: {}",
                        symbol, interval, fs.getStartTime(), e.getMessage());
            }
        }

        log.info("VCB backfill complete: {}/{} records updated for {}/{}",
                updated, missing.size(), symbol, interval);
        return updated;
    }

    /**
     * Signed Efficiency Ratio over N periods.
     *
     * Formula: (close[end] - close[end-N]) / sum(|close[i] - close[i-1]|) for i in [end-N+1, end]
     *
     * Range: -1.0 to +1.0
     * Positive = net upward directional efficiency
     * Negative = net downward directional efficiency
     * Near 0 = choppy / ranging
     *
     * This differs from the existing efficiencyRatio20 which takes abs() of
     * the numerator, losing the direction signal entirely.
     */
    private BigDecimal calculateSignedEfficiencyRatio(List<MarketData> data, int period) {
        if (data.size() < period + 1) return BigDecimal.ZERO;

        int end   = data.size() - 1;
        int start = end - period;

        // Net directional change (signed)
        BigDecimal direction = data.get(end).getClosePrice()
                .subtract(data.get(start).getClosePrice()); // NOT .abs()

        // Sum of absolute bar-to-bar changes (noise)
        BigDecimal noise = BigDecimal.ZERO;
        for (int i = start + 1; i <= end; i++) {
            noise = noise.add(
                    data.get(i).getClosePrice()
                            .subtract(data.get(i - 1).getClosePrice())
                            .abs()
            );
        }

        if (noise.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        // Clamp to [-1, 1] in case of floating point edge cases
        BigDecimal result = direction.divide(noise, 8, RoundingMode.HALF_UP);
        return result.max(new BigDecimal("-1")).min(BigDecimal.ONE);
    }
}

