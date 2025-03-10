package id.co.blackheart.service;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;


import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
@Slf4j
@AllArgsConstructor
public class TechnicalIndicatorService {

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;


    public FeatureStore computeIndicatorsAndStore(String symbol, Instant instantTimestamp) {
        List<MarketData> historicalData = marketDataRepository.findLast100BySymbolAndInterval(symbol, "1m");

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
        featureData.setTimestamp(timestamp);
        featureData.setPrice(price);

        featureData.setSma14(calculateSMA(historicalData, 14));
        featureData.setSma50(calculateSMA(historicalData, 50));
        featureData.setWma(calculateWMA(historicalData, 14));
        featureData.setMomentum(calculateMomentum(historicalData, 10));
        featureData.setStochK(calculateStochasticK(historicalData, 14));
        featureData.setStochD(calculateStochasticD(historicalData, 3));
        featureData.setMacd(calculateMACD(historicalData, 8, 21));  // Faster reaction time 1m trade
        featureData.setMacdSignal(calculateMACDSignal(historicalData, 5)); // Quick crossovers 1m trade
        featureData.setMacdHistogram(featureData.getMacd().subtract(featureData.getMacdSignal()));  // MACD Histogram  1m trade
        featureData.setRsi(calculateRSI(historicalData, 14));
        featureData.setWilliamsR(calculateWilliamsR(historicalData, 14));
        featureData.setCci(calculateCCI(historicalData, 20));
        featureData.setAdOscillator(calculateADOSC(historicalData));
        featureData.setVwap(calculateVWAP(historicalData));
        featureData.setAtr(calculateATR(historicalData, 14));
        featureData.setAdx(calculateADX(historicalData, 14));
        featureData.setPlusDI(calculatePlusDI(historicalData, 14));
        featureData.setMinusDI(calculateMinusDI(historicalData, 14));
        featureData.setEma9(calculateEMA(historicalData, 9));
        featureData.setEma14(calculateEMA(historicalData, 14));
        featureData.setEma21(calculateEMA(historicalData, 21));
        featureData.setEma50(calculateEMA(historicalData, 50));
        featureData.setBollingerMiddle(calculateBollingerMiddle(historicalData, 20));
        featureData.setBollingerUpper(calculateBollingerUpper(historicalData, 20));
        featureData.setBollingerLower(calculateBollingerLower(historicalData, 20));


        featureStoreRepository.save(featureData);

        return featureData;
    }



    private BigDecimal calculateSMA(List<MarketData> data, int period) {
        return data.stream().limit(period).map(MarketData::getClosePrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), RoundingMode.HALF_UP);
    }



    private BigDecimal calculateWMA(List<MarketData> data, int period) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            BigDecimal weight = BigDecimal.valueOf(period - i);
            weightedSum = weightedSum.add(data.get(i).getClosePrice().multiply(weight));
            denominator = denominator.add(weight);
        }
        return weightedSum.divide(denominator, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMomentum(List<MarketData> data, int period) {
        return data.getFirst().getClosePrice().subtract(data.get(period - 1).getClosePrice());
    }

    private BigDecimal calculateStochasticK(List<MarketData> data, int period) {
        BigDecimal highestHigh = data.stream().limit(period).map(MarketData::getHighPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal lowestLow = data.stream().limit(period).map(MarketData::getLowPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return (data.getFirst().getClosePrice().subtract(lowestLow))
                .divide(highestHigh.subtract(lowestLow), RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateStochasticD(List<MarketData> data, int period) {
        return calculateSMA(data, period);
    }

    // Compute MACD Line (Fast EMA - Slow EMA)
    public BigDecimal calculateMACD(List<MarketData> data, int shortPeriod, int longPeriod) {
        BigDecimal fastEMA = calculateEMA(data, shortPeriod);
        BigDecimal slowEMA = calculateEMA(data, longPeriod);
        return fastEMA.subtract(slowEMA);
    }

    // Compute MACD Signal Line (9-period EMA of MACD)
    public BigDecimal calculateMACDSignal(List<MarketData> data, int period) {
        if (data == null || data.size() < period) return BigDecimal.ZERO;

        // Step 1: Compute MACD values for each closing price
        List<BigDecimal> macdValues = data.stream()
                .map(m -> calculateMACD(data, 8, 21)) // Compute MACD per closing price
                .collect(Collectors.toList());

        // Step 2: Apply EMA to MACD values (not closing prices)
        return calculateEMAFromValues(macdValues, period);
    }

    // Compute EMA for closing prices (original function)
    private BigDecimal calculateEMA(List<MarketData> data, int period) {
        if (data == null || data.size() < period) return BigDecimal.ZERO;

        // Compute initial SMA for first 'period' data points
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(data.get(i).getClosePrice());
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        // Compute the EMA using the smoothing multiplier
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1.0));

        // Apply EMA formula recursively for remaining data points
        for (int i = period; i < data.size(); i++) {
            BigDecimal closePrice = data.get(i).getClosePrice();
            ema = closePrice.multiply(multiplier).add(ema.multiply(BigDecimal.ONE.subtract(multiplier)))
                    .setScale(8, RoundingMode.HALF_UP); // Ensure precision
        }

        return ema;
    }

    // Compute EMA from a list of values (for MACD Signal Line)
    private BigDecimal calculateEMAFromValues(List<BigDecimal> values, int period) {
        if (values.size() < period) return BigDecimal.ZERO;  // Not enough data

        // Compute Initial SMA for the first 'period' values
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(values.get(i));
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        // Compute the EMA using the smoothing multiplier
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1.0));

        // Apply EMA formula recursively for remaining values
        for (int i = period; i < values.size(); i++) {
            BigDecimal currentValue = values.get(i);
            ema = currentValue.multiply(multiplier).add(ema.multiply(BigDecimal.ONE.subtract(multiplier)))
                    .setScale(8, RoundingMode.HALF_UP); // Ensure precision
        }

        return ema;
    }


    private BigDecimal calculateRSI(List<MarketData> data, int period) {
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (int i = 1; i < period; i++) {
            BigDecimal change = data.get(i).getClosePrice().subtract(data.get(i - 1).getClosePrice());
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                gains = gains.add(change);
            } else {
                losses = losses.add(change.abs());
            }
        }
        BigDecimal avgGain = gains.divide(BigDecimal.valueOf(period), RoundingMode.HALF_UP);
        BigDecimal avgLoss = losses.divide(BigDecimal.valueOf(period), RoundingMode.HALF_UP);
        if (avgLoss.equals(BigDecimal.ZERO)) return BigDecimal.valueOf(100);
        BigDecimal rs = avgGain.divide(avgLoss, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100).divide(rs.add(BigDecimal.ONE), RoundingMode.HALF_UP));
    }

    private BigDecimal calculateWilliamsR(List<MarketData> data, int period) {
        BigDecimal highestHigh = data.stream().limit(period).map(MarketData::getHighPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal lowestLow = data.stream().limit(period).map(MarketData::getLowPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        if (highestHigh.equals(lowestLow)) return BigDecimal.ZERO; // Avoid division by zero

        return (highestHigh.subtract(data.get(0).getClosePrice()))
                .divide(highestHigh.subtract(lowestLow), RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(-100)); // Reverse sign
    }

    private BigDecimal calculateCCI(List<MarketData> data, int period) {
        BigDecimal typicalPrice = data.stream().limit(period)
                .map(d -> (d.getHighPrice().add(d.getLowPrice()).add(d.getClosePrice())).divide(BigDecimal.valueOf(3), RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), RoundingMode.HALF_UP);

        BigDecimal sma = calculateSMA(data, period);
        BigDecimal meanDeviation = data.stream().limit(period)
                .map(d -> d.getClosePrice().subtract(sma).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), RoundingMode.HALF_UP);

        return (typicalPrice.subtract(sma))
                .divide(meanDeviation.multiply(BigDecimal.valueOf(0.015)), RoundingMode.HALF_UP);
    }

    private BigDecimal calculateADOSC(List<MarketData> data) {
        List<BigDecimal> moneyFlowVolumes = data.stream().map(d ->
                ((d.getClosePrice().subtract(d.getLowPrice()))
                        .subtract(d.getHighPrice().subtract(d.getClosePrice())))
                        .divide(d.getHighPrice().subtract(d.getLowPrice()), RoundingMode.HALF_UP)
                        .multiply(d.getVolume())
        ).toList();

        BigDecimal fastEMA = calculateEMAPrice(moneyFlowVolumes, 3);
        BigDecimal slowEMA = calculateEMAPrice(moneyFlowVolumes, 10);
        return fastEMA.subtract(slowEMA);
    }


    private BigDecimal calculateEMAPrice(List<BigDecimal> prices, int period) {
        if (prices.size() < period) {
            return BigDecimal.ZERO;
        }
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1.0));
        BigDecimal ema = prices.get(prices.size() - period);
        for (int i = prices.size() - period + 1; i < prices.size(); i++) {
            ema = prices.get(i).multiply(multiplier).add(
                    ema.multiply(BigDecimal.ONE.subtract(multiplier)));
        }
        return ema;
    }

    private BigDecimal calculateVWAP(List<MarketData> data) {
        if (data == null || data.isEmpty()) return BigDecimal.ZERO;

        BigDecimal cumulativeVolume = BigDecimal.ZERO;
        BigDecimal cumulativePriceVolume = BigDecimal.ZERO;

        for (MarketData entry : data) { // No window limit, use all data
            BigDecimal high = entry.getHighPrice();
            BigDecimal low = entry.getLowPrice();
            BigDecimal close = entry.getClosePrice();
            BigDecimal volume = entry.getVolume();

            // Calculate typical price: (High + Low + Close) / 3
            BigDecimal typicalPrice = high.add(low).add(close).divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_UP);

            // Multiply by volume
            BigDecimal priceVolume = typicalPrice.multiply(volume);

            // Sum up price * volume and total volume
            cumulativePriceVolume = cumulativePriceVolume.add(priceVolume);
            cumulativeVolume = cumulativeVolume.add(volume);
        }

        // Avoid division by zero
        return (cumulativeVolume.compareTo(BigDecimal.ZERO) == 0)
                ? BigDecimal.ZERO
                : cumulativePriceVolume.divide(cumulativeVolume, 8, RoundingMode.HALF_UP);
    }



    private BigDecimal calculateATR(List<MarketData> data, int period) {
        if (data == null || data.size() < period) return BigDecimal.ZERO;

        BigDecimal sumTrueRange = BigDecimal.ZERO;
        BigDecimal prevATR = BigDecimal.ZERO;

        // Calculate initial ATR as the simple average of True Ranges
        for (int i = 1; i <= period; i++) {
            BigDecimal highLow = data.get(i).getHighPrice().subtract(data.get(i).getLowPrice());
            BigDecimal highClosePrev = data.get(i).getHighPrice().subtract(data.get(i - 1).getClosePrice()).abs();
            BigDecimal lowClosePrev = data.get(i).getLowPrice().subtract(data.get(i - 1).getClosePrice()).abs();

            BigDecimal trueRange = highLow.max(highClosePrev).max(lowClosePrev);
            sumTrueRange = sumTrueRange.add(trueRange);
        }

        // First ATR (Simple Moving Average of True Ranges)
        prevATR = sumTrueRange.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        // Calculate subsequent ATRs using Wilder's smoothing method
        for (int i = period; i < data.size(); i++) {
            BigDecimal highLow = data.get(i).getHighPrice().subtract(data.get(i).getLowPrice());
            BigDecimal highClosePrev = data.get(i).getHighPrice().subtract(data.get(i - 1).getClosePrice()).abs();
            BigDecimal lowClosePrev = data.get(i).getLowPrice().subtract(data.get(i - 1).getClosePrice()).abs();

            BigDecimal trueRange = highLow.max(highClosePrev).max(lowClosePrev);

            // Wilder's ATR Formula: ATR_t = (ATR_(t-1) * (period - 1) + TR_t) / period
            prevATR = prevATR.multiply(BigDecimal.valueOf(period - 1))
                    .add(trueRange)
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        }

        return prevATR;
    }


    private BigDecimal calculateADX(List<MarketData> data, int period) {
        if (data.size() < period) return BigDecimal.ZERO;

        BigDecimal sumDX = BigDecimal.ZERO;
        BigDecimal prevADX = BigDecimal.ZERO;

        for (int i = period; i < data.size(); i++) {
            BigDecimal plusDI = calculatePlusDI(data.subList(i - period, i), period);
            BigDecimal minusDI = calculateMinusDI(data.subList(i - period, i), period);

            BigDecimal denominator = plusDI.add(minusDI);
            if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                continue;  // Avoid division by zero
            }

            BigDecimal dx = plusDI.subtract(minusDI).abs()
                    .divide(denominator, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            sumDX = sumDX.add(dx);
        }

        return sumDX.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePlusDI(List<MarketData> data, int period) {
        BigDecimal sumDMPlus = BigDecimal.ZERO;
        BigDecimal sumTR = BigDecimal.ZERO;

        for (int i = 1; i < period; i++) {
            BigDecimal highDiff = data.get(i).getHighPrice().subtract(data.get(i - 1).getHighPrice());
            BigDecimal lowDiff = data.get(i - 1).getLowPrice().subtract(data.get(i).getLowPrice());

            BigDecimal dmPlus = highDiff.compareTo(lowDiff) > 0 && highDiff.compareTo(BigDecimal.ZERO) > 0 ? highDiff : BigDecimal.ZERO;

            BigDecimal trueRange = calculateTrueRange(data.get(i), data.get(i - 1));

            sumDMPlus = sumDMPlus.add(dmPlus);
            sumTR = sumTR.add(trueRange);
        }

        if (sumTR.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO; // Avoid division by zero

        return sumDMPlus.divide(sumTR, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateMinusDI(List<MarketData> data, int period) {
        BigDecimal sumDMMinus = BigDecimal.ZERO;
        BigDecimal sumTR = BigDecimal.ZERO;

        for (int i = 1; i < period; i++) {
            BigDecimal highDiff = data.get(i).getHighPrice().subtract(data.get(i - 1).getHighPrice());
            BigDecimal lowDiff = data.get(i - 1).getLowPrice().subtract(data.get(i).getLowPrice());

            BigDecimal dmMinus = lowDiff.compareTo(highDiff) > 0 && lowDiff.compareTo(BigDecimal.ZERO) > 0 ? lowDiff : BigDecimal.ZERO;

            BigDecimal trueRange = calculateTrueRange(data.get(i), data.get(i - 1));

            sumDMMinus = sumDMMinus.add(dmMinus);
            sumTR = sumTR.add(trueRange);
        }

        if (sumTR.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO; // Avoid division by zero

        return sumDMMinus.divide(sumTR, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }


    private BigDecimal calculateTrueRange(MarketData current, MarketData previous) {
        BigDecimal highLow = current.getHighPrice().subtract(current.getLowPrice());
        BigDecimal highClosePrev = current.getHighPrice().subtract(previous.getClosePrice()).abs();
        BigDecimal lowClosePrev = current.getLowPrice().subtract(previous.getClosePrice()).abs();
        return highLow.max(highClosePrev).max(lowClosePrev);
    }


    private BigDecimal calculateBollingerMiddle(List<MarketData> data, int period) {
        if (data.size() < period) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = data.size() - period; i < data.size(); i++) {
            sum = sum.add(data.get(i).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateBollingerUpper(List<MarketData> data, int period) {
        BigDecimal middle = calculateBollingerMiddle(data, period);
        BigDecimal standardDeviation = calculateStandardDeviation(data, period);
        return middle.add(standardDeviation.multiply(BigDecimal.valueOf(2)));
    }

    private BigDecimal calculateBollingerLower(List<MarketData> data, int period) {
        BigDecimal middle = calculateBollingerMiddle(data, period);
        BigDecimal standardDeviation = calculateStandardDeviation(data, period);
        return middle.subtract(standardDeviation.multiply(BigDecimal.valueOf(2)));
    }

    private BigDecimal calculateStandardDeviation(List<MarketData> data, int period) {
        if (data.size() < period) return BigDecimal.ZERO;
        BigDecimal mean = calculateBollingerMiddle(data, period);
        BigDecimal varianceSum = BigDecimal.ZERO;
        for (int i = data.size() - period; i < data.size(); i++) {
            BigDecimal diff = data.get(i).getClosePrice().subtract(mean);
            varianceSum = varianceSum.add(diff.multiply(diff));
        }
        BigDecimal variance = varianceSum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }
}

