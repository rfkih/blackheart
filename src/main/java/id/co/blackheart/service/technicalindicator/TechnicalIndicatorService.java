package id.co.blackheart.service.technicalindicator;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.FundingRate;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.FundingRateRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.funding.FundingRateService;
import id.co.blackheart.service.funding.FundingRateService.FundingFeatureSnapshot;
import id.co.blackheart.service.marketdata.job.JobContext;
import id.co.blackheart.util.MapperUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TechnicalIndicatorService {

    /** Bars of warmup loaded before each computation target — matches the
     *  live single-bar path's findLast300 query, so backfilled values are
     *  saturated to the same degree as live. */
    private static final int LIVE_WARMUP_BARS = 300;

    /** Save-buffer size during chunked feature_store writes. Bounds Hibernate
     *  L1 cache memory between flush+clear cycles. */
    private static final int CHUNK_SIZE = 500;

    /** Length of one bulk-backfill window. Each window opens its own
     *  REQUIRES_NEW transaction so cancellation rolls back at most one
     *  window's worth of work. */
    private static final int WINDOW_MONTHS = 1;

    /** Soft minimum on warmup data — fewer rows than this won't saturate
     *  long-period indicators (EMA200 in particular). Returns null/zero from
     *  the live path; bulk path skips the window. */
    private static final int MIN_WARMUP_ROWS = 250;

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final FundingRateRepository fundingRateRepository;
    private final FundingRateService fundingRateService;
    private final MapperUtil mapperUtil;
    private final PlatformTransactionManager transactionManager;

    // Field-injected so it cooperates with @RequiredArgsConstructor — Lombok
    // skips non-final fields when generating the constructor, leaving
    // @PersistenceContext to provide the transaction-bound EntityManager.
    @PersistenceContext
    private EntityManager entityManager;


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

        if (historicalData == null || historicalData.size() < MIN_WARMUP_ROWS) {
            log.warn("Not enough historical data to compute indicators for {} {}", symbol, interval);
            return null;
        }

        historicalData.sort(Comparator.comparing(MarketData::getStartTime));
        MarketData latestMarketData = historicalData.getLast();

        Optional<FeatureStore> existingFeature = featureStoreRepository.findBySymbolAndIntervalAndStartTime(
                symbol, interval, latestMarketData.getStartTime());
        if (existingFeature.isPresent()) {
            log.debug("Feature already exists. symbol={} interval={} startTime={}",
                    symbol, interval, latestMarketData.getStartTime());
            return existingFeature.get();
        }

        // Single-bar path: build indicators on the 300-bar window, then defer
        // the row construction to the shared helper used by bulk backfill.
        // Live behavior is preserved exactly — the helper mirrors the prior
        // inline implementation column-for-column.
        BarSeries series = convertToBarSeries(historicalData, interval);
        IndicatorBundle ind = IndicatorBundle.build(series);
        int endIndex = series.getEndIndex();

        FundingFeatureSnapshot funding = fundingRateService.computeFundingFeatures(
                symbol, latestMarketData.getEndTime());

        FeatureStore featureData = buildFeatureRowFromPrecomputed(
                endIndex, symbol, interval, historicalData, ind, funding);
        return featureStoreRepository.save(featureData);
    }

    /**
     * Builds one {@link FeatureStore} row from precomputed TA4j indicators at
     * the given bar index. Shared between the live single-bar path
     * ({@link #computeIndicatorsAndStore}) and the bulk backfill path
     * ({@link #bulkComputeAndStoreInRange}) — produces the same column values
     * for the same input data, so backfilled rows and live rows are
     * indistinguishable.
     *
     * <p>{@code dataUpToIdx} must be the prefix of the loaded market_data
     * ending at {@code idx} inclusive, sorted ascending by start_time. The
     * helpers ({@code calculateRelativeVolume}, {@code calculateAtrRatio},
     * etc.) read the tail of this list and assume the last element is the
     * target bar.
     */
    private FeatureStore buildFeatureRowFromPrecomputed(
            int idx,
            String symbol,
            String interval,
            List<MarketData> dataUpToIdx,
            IndicatorBundle ind,
            FundingFeatureSnapshot funding
    ) {
        MarketData md = dataUpToIdx.get(dataUpToIdx.size() - 1);
        BigDecimal price = md.getClosePrice();

        FeatureStore f = new FeatureStore();
        f.setIdMarketData(md.getId());
        f.setSymbol(symbol);
        f.setInterval(interval);
        f.setStartTime(md.getStartTime());
        f.setEndTime(md.getEndTime());
        f.setPrice(price);

        // Trend
        f.setEma20(toBigDecimal(ind.ema20.getValue(idx)));
        f.setEma50(toBigDecimal(ind.ema50.getValue(idx)));
        f.setEma200(toBigDecimal(ind.ema200.getValue(idx)));

        if (idx > 0) {
            f.setEma50Slope(toBigDecimal(ind.ema50.getValue(idx).minus(ind.ema50.getValue(idx - 1))));
            f.setEma200Slope(toBigDecimal(ind.ema200.getValue(idx).minus(ind.ema200.getValue(idx - 1))));
        } else {
            f.setEma50Slope(BigDecimal.ZERO);
            f.setEma200Slope(BigDecimal.ZERO);
        }

        if (idx >= 200) {
            f.setSlope200(
                    toBigDecimal(ind.ema200.getValue(idx).minus(ind.ema200.getValue(idx - 200)))
                            .divide(new BigDecimal("200"), 8, RoundingMode.HALF_UP)
            );
        } else if (idx > 0) {
            f.setSlope200(
                    toBigDecimal(ind.ema200.getValue(idx).minus(ind.ema200.getValue(0)))
                            .divide(BigDecimal.valueOf(idx), 8, RoundingMode.HALF_UP)
            );
        } else {
            f.setSlope200(BigDecimal.ZERO);
        }

        // Trend strength
        f.setAdx(toBigDecimal(ind.adx.getValue(idx)));
        f.setPlusDI(toBigDecimal(ind.plusDI.getValue(idx)));
        f.setMinusDI(toBigDecimal(ind.minusDI.getValue(idx)));
        f.setEfficiencyRatio20(calculateEfficiencyRatio(dataUpToIdx, 20));

        // Volatility
        BigDecimal atrValue = toBigDecimal(ind.atr.getValue(idx));
        f.setAtr(atrValue);
        f.setAtrPct(
                price.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : atrValue.divide(price, 8, RoundingMode.HALF_UP)
        );

        // Momentum
        BigDecimal macdValue = toBigDecimal(ind.macd.getValue(idx));
        BigDecimal macdSignalValue = toBigDecimal(ind.macdSignal.getValue(idx));
        f.setMacd(macdValue);
        f.setMacdSignal(macdSignalValue);
        f.setMacdHistogram(macdValue.subtract(macdSignalValue));
        f.setRsi(toBigDecimal(ind.rsi.getValue(idx)));

        // Structure / breakout
        f.setHighestHigh20(calculateHighestHigh(dataUpToIdx, 20));
        f.setLowestLow20(calculateLowestLow(dataUpToIdx, 20));
        f.setDonchianUpper20(f.getHighestHigh20());
        f.setDonchianLower20(f.getLowestLow20());
        f.setDonchianMid20(
                f.getDonchianUpper20()
                        .add(f.getDonchianLower20())
                        .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP)
        );

        // Candle quality
        BigDecimal open = md.getOpenPrice();
        BigDecimal high = md.getHighPrice();
        BigDecimal low = md.getLowPrice();
        BigDecimal close = md.getClosePrice();
        BigDecimal bodySize = close.subtract(open).abs();
        BigDecimal candleRange = high.subtract(low);
        f.setBodySize(bodySize);
        f.setCandleRange(candleRange);
        f.setBodyToRangeRatio(
                candleRange.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : bodySize.divide(candleRange, 8, RoundingMode.HALF_UP)
        );
        f.setCloseLocationValue(
                candleRange.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : close.subtract(low).divide(candleRange, 8, RoundingMode.HALF_UP)
        );
        f.setRelativeVolume20(calculateRelativeVolume(dataUpToIdx, 20));

        // Regime summary
        Integer trendScore = calculateTrendScore(f, price);
        f.setTrendScore(trendScore);
        f.setTrendRegime(resolveTrendRegime(trendScore));
        f.setVolatilityRegime(resolveVolatilityRegime(f.getAtrPct()));
        f.setIsBearishBreakout(isBearishBreakout(f, price));
        f.setIsBullishBreakout(isBullishBreakout(f, price));
        f.setIsBullishPullback(isBullishPullback(f, price));
        f.setIsBearishPullback(isBearishPullback(f, price));
        f.setEntryBias(resolveEntryBias(f));

        // VCB band features
        Num bbUpperValue = ind.ema20.getValue(idx)
                .plus(ind.stdDev20.getValue(idx).multipliedBy(ind.series.numOf(2)));
        Num bbLowerValue = ind.ema20.getValue(idx)
                .minus(ind.stdDev20.getValue(idx).multipliedBy(ind.series.numOf(2)));
        BigDecimal bbUpper = toBigDecimal(bbUpperValue);
        BigDecimal bbLower = toBigDecimal(bbLowerValue);
        BigDecimal ema20Value = toBigDecimal(ind.ema20.getValue(idx));
        f.setBbUpperBand(bbUpper);
        f.setBbLowerBand(bbLower);
        f.setBbWidth(
                ema20Value.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : bbUpper.subtract(bbLower).divide(ema20Value, 8, RoundingMode.HALF_UP)
        );

        BigDecimal kcMultiplier = new BigDecimal("1.5");
        BigDecimal kcUpper = ema20Value.add(atrValue.multiply(kcMultiplier));
        BigDecimal kcLower = ema20Value.subtract(atrValue.multiply(kcMultiplier));
        f.setKcUpperBand(kcUpper);
        f.setKcLowerBand(kcLower);
        f.setKcWidth(
                ema20Value.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : kcUpper.subtract(kcLower).divide(ema20Value, 8, RoundingMode.HALF_UP)
        );

        f.setAtrRatio(calculateAtrRatio(dataUpToIdx, 14, 20));
        f.setSignedEr20(calculateSignedEfficiencyRatio(dataUpToIdx, 20));

        // Funding (V35) — perp-only; null on spot symbols or when
        // funding_rate_history is cold-started for this symbol.
        f.setFundingRate8h(funding.rate8h());
        f.setFundingRate7dAvg(funding.rate7dAvg());
        f.setFundingRateZ(funding.rateZ());

        return f;
    }

    /**
     * Bundle of TA4j indicators built once over a {@link BarSeries}. Keeps the
     * bulk-backfill path's signature manageable and guarantees the indicator
     * set stays aligned with the live single-bar path.
     */
    private static final class IndicatorBundle {
        final BarSeries series;
        final ClosePriceIndicator closePrice;
        final EMAIndicator ema20;
        final EMAIndicator ema50;
        final EMAIndicator ema200;
        final ADXIndicator adx;
        final PlusDIIndicator plusDI;
        final MinusDIIndicator minusDI;
        final ATRIndicator atr;
        final MACDIndicator macd;
        final EMAIndicator macdSignal;
        final RSIIndicator rsi;
        final StandardDeviationIndicator stdDev20;

        private IndicatorBundle(BarSeries series, ClosePriceIndicator closePrice,
                                EMAIndicator ema20, EMAIndicator ema50, EMAIndicator ema200,
                                ADXIndicator adx, PlusDIIndicator plusDI, MinusDIIndicator minusDI,
                                ATRIndicator atr, MACDIndicator macd, EMAIndicator macdSignal,
                                RSIIndicator rsi, StandardDeviationIndicator stdDev20) {
            this.series = series;
            this.closePrice = closePrice;
            this.ema20 = ema20;
            this.ema50 = ema50;
            this.ema200 = ema200;
            this.adx = adx;
            this.plusDI = plusDI;
            this.minusDI = minusDI;
            this.atr = atr;
            this.macd = macd;
            this.macdSignal = macdSignal;
            this.rsi = rsi;
            this.stdDev20 = stdDev20;
        }

        static IndicatorBundle build(BarSeries series) {
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
            return new IndicatorBundle(
                    series, closePrice,
                    new EMAIndicator(closePrice, 20),
                    new EMAIndicator(closePrice, 50),
                    new EMAIndicator(closePrice, 200),
                    new ADXIndicator(series, 14),
                    new PlusDIIndicator(series, 14),
                    new MinusDIIndicator(series, 14),
                    new ATRIndicator(series, 14),
                    macd,
                    new EMAIndicator(macd, 9),
                    new RSIIndicator(closePrice, 14),
                    new StandardDeviationIndicator(closePrice, 20)
            );
        }
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
        return BarSeriesUtil.toBigDecimal(value);
    }

    private BarSeries convertToBarSeries(List<MarketData> historicalData, String interval) {
        return BarSeriesUtil.toBarSeries(historicalData, interval, mapperUtil);
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
     * Bulk feature backfill — chunks the {@code [from, to]} range into
     * 1-month windows and processes each in its own {@code REQUIRES_NEW}
     * transaction. Within each window: loads market_data with a 300-bar
     * warmup, builds the BarSeries + indicators once, walks target bars in
     * O(N), saves in chunks of 500 with {@code flush() + clear()} so the
     * Hibernate L1 cache stays bounded.
     *
     * <p><b>Warmup parity</b>: each window pulls a fresh 300 bars before
     * {@code windowStart}, so target-bar indicator values are computed with
     * the same effective lookback as the live single-bar path. Backfilled
     * rows match what live ingestion would have produced for the same
     * {@code start_time}.
     *
     * <p><b>Memory</b>: peak heap is bounded by one 1-month window's bars
     * + the 500-row save buffer. A multi-year backfill no longer risks OOM.
     *
     * <p><b>Cancellation</b>: cooperative — the runner flips
     * {@code cancel_requested} and the loop checks {@code ctx.isCancellationRequested()}
     * between chunks. On cancel, the current window is rolled back via
     * {@code setRollbackOnly()} (no half-saved window), and previously
     * committed windows remain. Cancel latency is bounded by chunk processing
     * time (~1s per 500 rows).
     *
     * <p><b>Recompute</b>: when {@code recompute=true} the per-window delete
     * happens inside that window's transaction, so a cancelled window's
     * deletes also roll back. Previously committed windows remain in their
     * recomputed state.
     *
     * <p>Optional {@code ctx} drives progress emission and cooperative cancel
     * — pass null when called from a non-job context (legacy sync endpoint
     * or tests).
     */
    public BulkBackfillResult bulkComputeAndStoreInRange(String symbol, String interval,
                                                         LocalDateTime from, LocalDateTime to,
                                                         boolean recompute, JobContext ctx) {
        long intervalMinutes = mapperUtil.getIntervalMinutes(interval);

        // Pre-count target candidates across the whole range for stable
        // progress totals — small aggregate query, not a partition scan.
        long totalCandidates = marketDataRepository
                .countBySymbolIntervalAndRange(symbol, interval, from, to);
        if (totalCandidates <= 0) {
            log.info("Bulk backfill: no market_data in [{} → {}] for {}/{}", from, to, symbol, interval);
            return new BulkBackfillResult(0, 0, 0, 0);
        }
        if (ctx != null) {
            ctx.setPhase("bulk:walking_windows");
            ctx.setProgress(0, (int) Math.min(totalCandidates, Integer.MAX_VALUE));
        }
        log.info("Bulk backfill: {} target candle(s) for {}/{} [{} → {}] (recompute={})",
                totalCandidates, symbol, interval, from, to, recompute);

        // Pre-flight: warn loudly if funding_rate_history is cold for this
        // symbol. The bulk path does not auto-trigger the funding backfill —
        // every feature_store row written here will have NULL funding
        // columns, and the downstream PATCH_NULL_COLUMN job (or the
        // chained patch in BACKFILL_FUNDING_HISTORY) will need to rescue
        // them later. Surfacing this upfront prevents the silent-NULL
        // class of bugs that masqueraded as "patch ran but did nothing".
        if (fundingRateRepository.countBySymbol(symbol) == 0) {
            log.warn("Bulk backfill: funding_rate_history is cold for symbol={} — feature_store rows " +
                            "will be written with NULL funding columns. Run BACKFILL_FUNDING_HISTORY " +
                            "for {} first if it's a perpetual symbol; otherwise the chained patch " +
                            "after the next funding-history backfill will fill them retroactively.",
                    symbol, symbol);
        }

        TransactionTemplate windowTx = new TransactionTemplate(transactionManager);
        windowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        int totalInserted = 0;
        int totalSkipped = 0;
        int totalFailed = 0;
        int totalProcessed = 0;

        LocalDateTime windowStart = from;
        while (!windowStart.isAfter(to)) {
            if (ctx != null && ctx.isCancellationRequested()) {
                log.info("Bulk backfill cancelled before window starting at {} for {}/{}",
                        windowStart, symbol, interval);
                break;
            }
            LocalDateTime windowEnd = windowStart.plusMonths(WINDOW_MONTHS);
            if (windowEnd.isAfter(to)) windowEnd = to;
            final LocalDateTime ws = windowStart;
            final LocalDateTime we = windowEnd;
            final int progressBaseline = totalProcessed;

            WindowResult res = windowTx.execute(status -> processBulkWindow(
                    symbol, interval, ws, we, intervalMinutes, recompute, ctx,
                    progressBaseline, (int) Math.min(totalCandidates, Integer.MAX_VALUE),
                    status));

            if (res != null) {
                totalInserted += res.inserted;
                totalSkipped += res.skipped;
                totalFailed += res.failed;
                totalProcessed += res.processed;
                if (res.cancelled) {
                    log.info("Bulk backfill: window [{} → {}] rolled back on cancel for {}/{}",
                            ws, we, symbol, interval);
                    break;
                }
            }

            windowStart = windowEnd.plusNanos(1);
        }

        if (ctx != null && !(ctx.isCancellationRequested())) {
            ctx.setProgress((int) Math.min(totalCandidates, Integer.MAX_VALUE),
                    (int) Math.min(totalCandidates, Integer.MAX_VALUE));
        }

        log.info("Bulk backfill complete: inserted={} skipped={} failed={} totalProcessed={} for {}/{} (recompute={})",
                totalInserted, totalSkipped, totalFailed, totalProcessed, symbol, interval, recompute);
        return new BulkBackfillResult(totalInserted, totalSkipped, totalFailed,
                (int) Math.min(totalCandidates, Integer.MAX_VALUE));
    }

    /**
     * Processes one 1-month window inside the caller's
     * {@code REQUIRES_NEW} transaction. Returns a {@link WindowResult};
     * sets the transaction status to rollback when cancellation is observed
     * mid-window, so partial deletes/inserts are reverted.
     */
    private WindowResult processBulkWindow(String symbol, String interval,
                                           LocalDateTime windowStart, LocalDateTime windowEnd,
                                           long intervalMinutes, boolean recompute,
                                           JobContext ctx, int progressBaseline, int progressTotal,
                                           org.springframework.transaction.TransactionStatus txStatus) {
        if (ctx != null) {
            ctx.setPhase("bulk:" + windowStart.toLocalDate());
        }

        // Per-window delete (when recompute=true) so cancel rolls back THIS
        // window's deletes too — not just inserts. Avoids leaving "deleted
        // but not yet recomputed" gaps when a recompute is cancelled.
        if (recompute) {
            int deleted = featureStoreRepository.deleteBySymbolAndIntervalInRange(
                    symbol, interval, windowStart, windowEnd);
            log.debug("Bulk backfill (recompute) window [{} → {}]: deleted {} rows",
                    windowStart, windowEnd, deleted);
        }

        // Load the per-window data with LIVE_WARMUP_BARS bars of warmup
        // before windowStart — matches the live single-bar path's lookback,
        // so indicator values stay parity.
        // Use the start_time-range variant so the bar AT exactly
        // windowEnd is included (otherwise its end_time spills past
        // windowEnd and findBySymbolIntervalAndRange would drop it,
        // leaving feature_store gaps at every window boundary).
        LocalDateTime warmupStart = windowStart.minusMinutes((long) LIVE_WARMUP_BARS * intervalMinutes);
        List<MarketData> data = marketDataRepository.findBySymbolIntervalAndStartTimeRange(
                symbol, interval, warmupStart, windowEnd);
        if (CollectionUtils.isEmpty(data)) {
            return new WindowResult(0, 0, 0, 0, false);
        }
        data.sort(Comparator.comparing(MarketData::getStartTime));

        // Existing rows in window (post-delete in recompute mode → empty).
        Set<LocalDateTime> existing = new HashSet<>(featureStoreRepository
                .findExistingStartTimesInRange(symbol, interval, windowStart, windowEnd)
                .stream()
                .map(Timestamp::toLocalDateTime)
                .collect(Collectors.toSet()));

        BarSeries series = convertToBarSeries(data, interval);
        IndicatorBundle ind = IndicatorBundle.build(series);

        // Funding history up to (windowEnd + interval) — Spot symbols see empty.
        // The +interval overshoot covers the bar at start_time = windowEnd whose
        // end_time crosses into the next window; without it a funding event
        // landing on the boundary would be missed and the bulk row's funding
        // columns would diverge from the live single-bar path's findLatest()
        // semantics (which uses <= on end_time).
        LocalDateTime fundingBoundary = windowEnd.plusMinutes(intervalMinutes);
        List<FundingRate> fundingSeries = fundingRateRepository.findAllUpTo(symbol, fundingBoundary);

        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        int processed = 0;
        List<FeatureStore> buffer = new ArrayList<>(CHUNK_SIZE);

        for (int idx = 0; idx < data.size(); idx++) {
            MarketData md = data.get(idx);
            if (md.getStartTime().isBefore(windowStart)) continue;
            if (md.getStartTime().isAfter(windowEnd)) break;
            processed++;

            if (existing.contains(md.getStartTime())) {
                skipped++;
                continue;
            }

            if (ctx != null && ctx.isCancellationRequested()) {
                // Roll the WHOLE window back so a recompute doesn't leave
                // a half-replaced window and a fill-missing doesn't leave
                // a half-filled chunk that the operator can't easily detect.
                txStatus.setRollbackOnly();
                log.info("Bulk backfill window [{} → {}] rolling back on cancel for {}/{}",
                        windowStart, windowEnd, symbol, interval);
                return new WindowResult(0, 0, 0, processed, true);
            }

            try {
                FundingFeatureSnapshot funding = fundingRateService
                        .computeFundingFeaturesFromSeries(fundingSeries, md.getEndTime());
                FeatureStore row = buildFeatureRowFromPrecomputed(
                        idx, symbol, interval, data.subList(0, idx + 1), ind, funding);
                buffer.add(row);
                inserted++;

                if (buffer.size() >= CHUNK_SIZE) {
                    featureStoreRepository.saveAll(buffer);
                    featureStoreRepository.flush();
                    // Evict managed entities from the L1 cache. Without this,
                    // a wide window keeps every saved row in memory until
                    // commit, defeating chunked saves.
                    entityManager.clear();
                    buffer.clear();
                    if (ctx != null) ctx.setProgress(progressBaseline + processed, progressTotal);
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Bulk backfill failed at idx={} startTime={} for {}/{}: {}",
                        idx, md.getStartTime(), symbol, interval, e.getMessage());
            }
        }
        if (!buffer.isEmpty()) {
            featureStoreRepository.saveAll(buffer);
            featureStoreRepository.flush();
            entityManager.clear();
            buffer.clear();
        }
        if (ctx != null) ctx.setProgress(progressBaseline + processed, progressTotal);
        return new WindowResult(inserted, skipped, failed, processed, false);
    }

    /** Per-window outcome aggregated by {@link #bulkComputeAndStoreInRange}. */
    private static final class WindowResult {
        final int inserted;
        final int skipped;
        final int failed;
        final int processed;
        final boolean cancelled;

        WindowResult(int inserted, int skipped, int failed, int processed, boolean cancelled) {
            this.inserted = inserted;
            this.skipped = skipped;
            this.failed = failed;
            this.processed = processed;
            this.cancelled = cancelled;
        }
    }

    /** Result tuple for {@link #bulkComputeAndStoreInRange}. */
    public record BulkBackfillResult(int inserted, int skipped, int failed, int total) {
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

