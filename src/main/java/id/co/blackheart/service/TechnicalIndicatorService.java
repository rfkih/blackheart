package id.co.blackheart.service;

import id.co.blackheart.dto.response.PredictionResponse;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.*;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.*;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;


import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Slf4j
@AllArgsConstructor
public class TechnicalIndicatorService {

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;


    public FeatureStore computeIndicatorsAndStore(String symbol, Instant instantTimestamp, PredictionResponse predictionResponse) {
        List<MarketData> historicalData = marketDataRepository.findLast100BySymbolAndInterval(symbol, "5m");

        FeatureStore featureStore = new FeatureStore();
        if (historicalData.size() < 50) {
            log.warn("Not enough historical data to compute indicators for {}", symbol);
            return featureStore;
        }

        MarketData latestMarketData = historicalData.getFirst();
        LocalDateTime timestamp = LocalDateTime.ofInstant(instantTimestamp, ZoneId.of("UTC"));
        BigDecimal price = latestMarketData.getClosePrice();

        FeatureStore featureData = new FeatureStore();
        featureData.setIdMarketData(latestMarketData.getId());
        featureData.setSymbol(symbol);
        featureData.setSignal(predictionResponse.getSignal());
        featureData.setConfidence(predictionResponse.getConfidence());
        featureData.setModel(predictionResponse.getModel());
        featureData.setTimestamp(timestamp);
        featureData.setPrice(price);

        // ✅ Convert MarketData to TA4J BarSeries
        BarSeries series = convertToBarSeries(historicalData);

        // ✅ Use TA4J Indicators
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        featureData.setSma14(new BigDecimal(new SMAIndicator(closePrice, 14)
                .getValue(series.getEndIndex()).toString()));

        featureData.setSma50(new BigDecimal(new SMAIndicator(closePrice, 50)
                .getValue(series.getEndIndex()).toString()));

        featureData.setWma(new BigDecimal(new WMAIndicator(closePrice, 14)
                .getValue(series.getEndIndex()).toString()));

        featureData.setMomentum(new BigDecimal( new ROCIndicator(closePrice, 10).getValue(series.getEndIndex()).toString()));

        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(series, 14);
        featureData.setStochK(new BigDecimal(stochK.getValue(series.getEndIndex()).toString()));

        StochasticOscillatorDIndicator stochD = new StochasticOscillatorDIndicator(stochK);
        featureData.setStochD(new BigDecimal(stochD.getValue(series.getEndIndex()).toString()));

        // ✅ Compute MACD (8, 21) and Signal Line (5)
        MACDIndicator macd = new MACDIndicator(closePrice, 8, 21);
        EMAIndicator macdSignal = new EMAIndicator(macd, 5);
        featureData.setMacd(new BigDecimal(macd.getValue(series.getEndIndex()).toString()));
        featureData.setMacdSignal(new BigDecimal(macdSignal.getValue(series.getEndIndex()).toString()));
        featureData.setMacdHistogram(new BigDecimal(macd.getValue(series.getEndIndex())
                .minus(macdSignal.getValue(series.getEndIndex())).toString()));

        // ✅ RSI (14)
        featureData.setRsi(new BigDecimal(new RSIIndicator(closePrice, 14)
                .getValue(series.getEndIndex()).toString()));

        // ✅ Williams %R (14)
        featureData.setWilliamsR(new BigDecimal(new WilliamsRIndicator(series, 14)
                .getValue(series.getEndIndex()).toString()));

        // ✅ CCI (20)
        featureData.setCci(new BigDecimal(new CCIIndicator(series, 20)
                .getValue(series.getEndIndex()).toString()));

        // ✅ AD Oscillator
        featureData.setAdOscillator(new BigDecimal(new AccumulationDistributionIndicator(series)
                .getValue(series.getEndIndex()).toString()));

        // ✅ VWAP
        featureData.setVwap(new BigDecimal(new VWAPIndicator(series, 14).getValue(series.getEndIndex()).toString()));


        // ✅ ATR (14)
        featureData.setAtr(new BigDecimal(new ATRIndicator(series, 14)
                .getValue(series.getEndIndex()).toString()));

        // ✅ ADX (14)
        ADXIndicator adx = new ADXIndicator(series, 14);
        featureData.setAdx(new BigDecimal(adx.getValue(series.getEndIndex()).toString()));

        // ✅ +DI and -DI (14)
        featureData.setPlusDI(new BigDecimal(new PlusDIIndicator(series, 14)
                .getValue(series.getEndIndex()).toString()));

        featureData.setMinusDI(new BigDecimal(new MinusDIIndicator(series, 14)
                .getValue(series.getEndIndex()).toString()));


        // ✅ EMA
        featureData.setEma9(new BigDecimal(new EMAIndicator(closePrice, 9)
                .getValue(series.getEndIndex()).toString()));

        featureData.setEma14(new BigDecimal(new EMAIndicator(closePrice, 14)
                .getValue(series.getEndIndex()).toString()));

        featureData.setEma21(new BigDecimal(new EMAIndicator(closePrice, 21)
                .getValue(series.getEndIndex()).toString()));

        featureData.setEma50(new BigDecimal(new EMAIndicator(closePrice, 50)
                .getValue(series.getEndIndex()).toString()));

        featureData.setEma100(new BigDecimal(new EMAIndicator(closePrice, 100)
                .getValue(series.getEndIndex()).toString()));

        // ✅ Bollinger Bands (20)
        BollingerBandsMiddleIndicator bbm = new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, 20));
        BollingerBandsUpperIndicator bbu = new BollingerBandsUpperIndicator(bbm, new StandardDeviationIndicator(closePrice, 20));
        BollingerBandsLowerIndicator bbl = new BollingerBandsLowerIndicator(bbm, new StandardDeviationIndicator(closePrice, 20));
        featureData.setBollingerMiddle(new BigDecimal(bbm.getValue(series.getEndIndex()).toString()));
        featureData.setBollingerUpper(new BigDecimal(bbu.getValue(series.getEndIndex()).toString()));
        featureData.setBollingerLower(new BigDecimal(bbl.getValue(series.getEndIndex()).toString()));


        featureStoreRepository.save(featureData);
        return featureData;
    }

    private BarSeries convertToBarSeries(List<MarketData> historicalData) {
        BarSeries series = new BaseBarSeries();

        Collections.reverse(historicalData);

        for (MarketData data : historicalData) {
            // ✅ Handle missing timestamp by generating one dynamically
            Instant barTimestamp = data.getEndTime().atZone(ZoneId.of("UTC")).toInstant();

            // ✅ Convert BigDecimal to Num using default Num.valueOf()
            Num openPrice = series.numOf(data.getOpenPrice());
            Num highPrice = series.numOf(data.getHighPrice());
            Num lowPrice = series.numOf(data.getLowPrice());
            Num closePrice = series.numOf(data.getClosePrice());
            Num volume = series.numOf(data.getVolume());

            // ✅ Use BaseBar.Builder with Num values
            BaseBar bar = BaseBar.builder()
                    .timePeriod(Duration.ofMinutes(1))  // 1-minute bars
                    .endTime(barTimestamp.atZone(ZoneId.of("UTC")))  // Correct timestamp handling
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

