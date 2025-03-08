package id.co.blackheart.service;


import id.co.blackheart.dto.TradeDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.TradesRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
@AllArgsConstructor
public class TradingService {
    private final TradesRepository tradesRepository;

    /**
     * Determines trade action based on market data and strategy.
     */
    public void vWapTradeAction(MarketData marketData, FeatureStore featureStore,
                                BigDecimal accountBalance, BigDecimal riskPercentage,
                                Long userId, String asset) {

        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        BigDecimal closePrice = marketData.getClosePrice();

        // Fetch Active Trade (if any)
        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActive(userId, asset, "1");

        TradeDecision decision = vWapTradeDecision(closePrice, featureStore, accountBalance, riskPercentage,
                marketData.getHighPrice(), activeTradeOpt, asset);

        // Execute Trade Action
        if ("BUY".equals(decision.getAction())) {
            Trades newTrade = new Trades();
             newTrade.setUserId(userId);
             newTrade.setAsset(asset);
             newTrade.setAction("BUY");
             newTrade.setPositionSize(decision.getPositionSize());
             newTrade.setStopLossPrice(decision.getStopLossPrice());
             newTrade.setTakeProfitPrice(decision.getTakeProfitPrice());
             newTrade.setIsActive("1");
            tradesRepository.save(newTrade);
            log.info("✅ BUY order placed for {} at {}", asset, closePrice);
        } else if ("SELL".equals(decision.getAction())) {
            activeTradeOpt.ifPresent(trade -> {
                trade.setIsActive("0");
                tradesRepository.save(trade);
                log.info("❌ SELL order executed. Trade closed for {} at {}", asset, closePrice);
            });
        } else {
            log.info("⏳ HOLD: No trade action needed for {} at {}", asset, closePrice);
        }
    }

    /**
     * Determines whether to BUY, SELL, or HOLD.
     */
    private TradeDecision vWapTradeDecision(BigDecimal closePrice, FeatureStore featureStore,
                                            BigDecimal accountBalance, BigDecimal riskPercentage,
                                            BigDecimal lastHighestPrice, Optional<Trades> activeTradeOpt,
                                            String asset) {

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.02);  // 2% stop-loss
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.05); // 5% take-profit
        BigDecimal trailingStopThreshold = BigDecimal.valueOf(0.02); // 2% trailing stop

        // Compute Stop-Loss and Take-Profit Levels
        BigDecimal stopLossPrice = closePrice.multiply(BigDecimal.ONE.subtract(stopLossThreshold));
        BigDecimal takeProfitPrice = closePrice.multiply(BigDecimal.ONE.add(takeProfitThreshold));

        // Compute Position Size based on Risk
        BigDecimal riskAmount = accountBalance.multiply(riskPercentage)
                .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
        BigDecimal positionSize = riskAmount.divide(closePrice.subtract(stopLossPrice), RoundingMode.HALF_UP);

        // Check if there is an active trade
        if (activeTradeOpt.isPresent()) {
            Trades activeTrade = activeTradeOpt.get();

            // Adjust Trailing Stop if Price Increases
            if (closePrice.compareTo(lastHighestPrice) > 0) {
                lastHighestPrice = closePrice;
                BigDecimal newStopLoss = lastHighestPrice.multiply(BigDecimal.ONE.subtract(trailingStopThreshold));
                activeTrade.setStopLossPrice(newStopLoss);
                tradesRepository.save(activeTrade);
                log.info("🔄 Trailing Stop Updated for {} to {}", asset, newStopLoss);
            }

            // Check if Stop-Loss or Take-Profit is Hit
            if (closePrice.compareTo(activeTrade.getStopLossPrice()) <= 0 ||
                    closePrice.compareTo(activeTrade.getTakeProfitPrice()) >= 0) {
                return TradeDecision.builder()
                        .action("SELL")
                        .positionSize(activeTrade.getPositionSize())
                        .stopLossPrice(activeTrade.getStopLossPrice())
                        .takeProfitPrice(activeTrade.getTakeProfitPrice())
                        .build();
            }
        } else {
            // Buying Condition (Only if there's no active trade)
            if (closePrice.compareTo(featureStore.getVwap()) > 0) {
                log.info("✅ BUY signal detected for {}", asset);
                return TradeDecision.builder()
                        .action("BUY")
                        .positionSize(positionSize)
                        .stopLossPrice(stopLossPrice)
                        .takeProfitPrice(takeProfitPrice)
                        .build();
            }
        }
        return TradeDecision.builder()
                .action("HOLD")
                .positionSize(BigDecimal.ZERO)
                .stopLossPrice(null)
                .takeProfitPrice(null)
                .build();
    }


    public void trendFollwoingTradeAction(MarketData marketData, FeatureStore featureStore,
                                          BigDecimal accountBalance, BigDecimal riskPercentage,
                                          Long userId, String asset) {

        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        BigDecimal closePrice = marketData.getClosePrice();

        // Fetch Active Trade (if any)
        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActive(userId, asset, "1");

        TradeDecision decision = trendFollowingTradeDecision(marketData, featureStore, accountBalance, riskPercentage,
                activeTradeOpt, asset);

        // Execute Trade Action
        if ("BUY".equals(decision.getAction())) {
            Trades newTrade = new Trades();
            newTrade.setUserId(userId);
            newTrade.setAsset(asset);
            newTrade.setAction("BUY");
            newTrade.setEntryPrice(marketData.getClosePrice());
            newTrade.setPositionSize(decision.getPositionSize());
            newTrade.setStopLossPrice(decision.getStopLossPrice());
            newTrade.setTakeProfitPrice(decision.getTakeProfitPrice());
            newTrade.setIsActive("1");
            newTrade.setEntryTime(LocalDateTime.now());
            tradesRepository.save(newTrade);
            log.info("✅ BUY order placed for {} at {}", asset, closePrice);
        } else if ("SELL".equals(decision.getAction())) {
            activeTradeOpt.ifPresent(trade -> {
                trade.setIsActive("0");
                trade.setExitTime(LocalDateTime.now());
                trade.setExitPrice(marketData.getClosePrice());
                tradesRepository.save(trade);
                log.info("❌ SELL order executed. Trade closed for {} at {}", asset, closePrice);
            });
        } else {
            log.info("⏳ HOLD: No trade action needed for {} at {}", asset, closePrice);
        }
    }

    /**
     * Determines whether to BUY, SELL, or HOLD based on moving averages.
     */
    private TradeDecision trendFollowingTradeDecision(MarketData marketData, FeatureStore featureStore,
                                                      BigDecimal accountBalance, BigDecimal riskPercentage,
                                                      Optional<Trades> activeTradeOpt, String asset) {

        BigDecimal closePrice = marketData.getClosePrice();
        BigDecimal ema9 = featureStore.getEma9();
        BigDecimal ema21 = featureStore.getEma21();
        BigDecimal sma50 = featureStore.getSma50();

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.02);  // 2% stop-loss
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.04); // 4% take-profit
        BigDecimal trailingStopThreshold = BigDecimal.valueOf(0.02); // 2% trailing stop

        // Compute Stop-Loss and Take-Profit Levels
        BigDecimal stopLossPrice = closePrice.multiply(BigDecimal.ONE.subtract(stopLossThreshold));
        BigDecimal takeProfitPrice = closePrice.multiply(BigDecimal.ONE.add(takeProfitThreshold));

        // Compute Position Size based on Risk
        BigDecimal riskAmount = accountBalance.multiply(riskPercentage)
                .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
        BigDecimal positionSize = riskAmount.divide(closePrice.subtract(stopLossPrice), RoundingMode.HALF_UP);

        // Check if there is an active trade
        if (activeTradeOpt.isPresent()) {
            Trades activeTrade = activeTradeOpt.get();

            // Adjust Trailing Stop if Price Increases
            if (closePrice.compareTo(activeTrade.getTakeProfitPrice()) > 0) {
                BigDecimal newStopLoss = closePrice.multiply(BigDecimal.ONE.subtract(trailingStopThreshold));
                activeTrade.setStopLossPrice(newStopLoss);
                tradesRepository.save(activeTrade);
                log.info("🔄 Trailing Stop Updated for {} to {}", asset, newStopLoss);
            }

            // Check if Stop-Loss or Take-Profit is Hit
            if (closePrice.compareTo(activeTrade.getStopLossPrice()) <= 0 ||
                    closePrice.compareTo(activeTrade.getTakeProfitPrice()) >= 0) {
                return TradeDecision.builder()
                        .action("SELL")
                        .positionSize(activeTrade.getPositionSize())
                        .stopLossPrice(activeTrade.getStopLossPrice())
                        .takeProfitPrice(activeTrade.getTakeProfitPrice())
                        .build();
            }
        } else {
            // Buying Condition (Only if there's no active trade)
            if (ema9.compareTo(ema21) > 0 && closePrice.compareTo(sma50) > 0) {
                log.info("✅ BUY signal detected for {} size {}", asset, positionSize);
                return TradeDecision.builder()
                        .action("BUY")
                        .positionSize(positionSize)
                        .stopLossPrice(stopLossPrice)
                        .takeProfitPrice(takeProfitPrice)
                        .build();
            }
        }
        return TradeDecision.builder()
                .action("HOLD")
                .positionSize(BigDecimal.ZERO)
                .stopLossPrice(null)
                .takeProfitPrice(null)
                .build();
    }



}
