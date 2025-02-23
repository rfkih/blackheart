package id.co.blackheart.service;


import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
public class TradingService {

    public String determineTradeAction(MarketData marketData, FeatureStore featureStore) {
        if (marketData == null) return "HOLD";

        // Strategy using VWAP, Bollinger Bands, and ADX
        boolean priceAboveVWAP = marketData.getClosePrice().compareTo(featureStore.getVwap()) > 0;
        boolean priceBelowVWAP = marketData.getClosePrice().compareTo(featureStore.getVwap()) < 0;

        boolean breakoutAboveBollinger = marketData.getClosePrice().compareTo(featureStore.getBollingerUpper()) > 0;
        boolean breakdownBelowBollinger = marketData.getClosePrice().compareTo(featureStore.getBollingerLower()) < 0;

        boolean strongTrend = featureStore.getAdx().compareTo(BigDecimal.valueOf(25)) > 0;
        boolean weakTrend = featureStore.getAdx().compareTo(BigDecimal.valueOf(20)) < 0;

        if (priceAboveVWAP && breakoutAboveBollinger && strongTrend) {
            return "BUY";
        } else if (priceBelowVWAP && breakdownBelowBollinger && strongTrend) {
            return "SELL";
        }
        return "HOLD";
    }
}
