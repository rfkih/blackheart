package id.co.blackheart.service;


import id.co.blackheart.dto.*;
import id.co.blackheart.model.*;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.util.TradeUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@Slf4j
@AllArgsConstructor
public class TradingService {
    private final TradesRepository tradesRepository;
    private final PortfolioRepository portfolioRepository;
    private final TradeExecutionService tradeExecutionService;
    private final TradeUtil tradeUtil;


    public void vwapMacdLongTradeAction(MarketData marketData, FeatureStore featureStore,
                                        BigDecimal accountBalance, BigDecimal riskPercentage,
                                        Users user, String asset) {
        String tradePlan = "vwapMacdLong";

        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        Optional<Portfolio> usdAsset = portfolioRepository.findByUserIdAndAsset(user.getId(), "USDT");

        if (!usdAsset.isPresent() || usdAsset.get().getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            log.info("No USDT Asset Found, cannot making trade action.");
            return;
        }

        BigDecimal closePrice = marketData.getClosePrice();

        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlanAndAction(user.getId(), asset, "1", tradePlan, "LONG");

        TradeDecision decision = vwapMacdTradeDecision(marketData, featureStore, accountBalance, riskPercentage, activeTradeOpt, asset);

        if ("BUY".equals(decision.getAction())) {
            LocalTime now = LocalTime.now(ZoneId.of("Asia/Jakarta"));
            log.info("now : " +now);
            log.info("getHour : " + now.getHour());
            if (now.getHour() >= 5) {
                log.info("🚫 Trading is disabled from 5 AM (Jakarta Time) until midnight. Skipping trade action.");
                return;
            }


            tradeUtil.openLongMarketOrder(user, asset, decision, tradePlan);
        } else if ("SELL".equals(decision.getAction())) {
            tradeUtil.closeLongMarketOrder(user, activeTradeOpt, marketData, asset);
        } else {
            log.info("⏳ HOLD: No Long trade action needed for {} at {}", asset, closePrice);
        }
    }

    /**
     * Determines whether to BUY, SELL, or HOLD based on VWAP and MACD strategy.
     */
    private TradeDecision vwapMacdTradeDecision(MarketData marketData, FeatureStore featureStore,
                                                BigDecimal accountBalance, BigDecimal riskPercentage,
                                                Optional<Trades> activeTradeOpt, String asset) {

        BigDecimal closePrice = marketData.getClosePrice();
        BigDecimal vwap = featureStore.getVwap();
        BigDecimal macd = featureStore.getMacd();
        BigDecimal macdSignal = featureStore.getMacdSignal();

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.003); // 0.3% Stop Loss
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.006); // 0.6% Take Profit
        BigDecimal trailingStopThreshold = BigDecimal.valueOf(0.002); // 0.2% Trailing Stop

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
                        .positionSize(activeTrade.getEntryExecutedQty())
                        .stopLossPrice(activeTrade.getStopLossPrice())
                        .takeProfitPrice(activeTrade.getTakeProfitPrice())
                        .build();
            }
        } else {
            // Buy Condition (No active trade)
            if (closePrice.compareTo(vwap) > 0 && macd.compareTo(macdSignal) > 0) {
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

    /**
     * Determines trade action based on market data and strategy.
     */
    public void vWapLongTradeAction(MarketData marketData, FeatureStore featureStore,
                                BigDecimal accountBalance, BigDecimal riskPercentage,
                                Users user, String asset) {
        String tradePlan = "vWapLong";
        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        BigDecimal closePrice = marketData.getClosePrice();

        // Fetch Active Trade (if any)
        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlanAndAction(user.getId(), asset, "1", tradePlan, "LONG");

        TradeDecision decision = vWapLongTradeDecision(closePrice, featureStore, accountBalance, riskPercentage,
                marketData.getHighPrice(), activeTradeOpt, asset);

        // Execute Trade Action
        if ("BUY".equals(decision.getAction())) {
            tradeUtil.openLongMarketOrder(user, asset, decision, tradePlan);
        } else if ("SELL".equals(decision.getAction())) {
            tradeUtil.closeLongMarketOrder(user,activeTradeOpt, marketData, asset);
        } else {
            log.info("⏳ HOLD: No trade action needed for {} at {}", asset, closePrice);
        }
    }

    /**
     * Determines whether to BUY, SELL, or HOLD.
     */
    private TradeDecision vWapLongTradeDecision(BigDecimal closePrice, FeatureStore featureStore,
                                                BigDecimal accountBalance, BigDecimal riskPercentage,
                                                BigDecimal lastHighestPrice, Optional<Trades> activeTradeOpt,
                                                String asset) {

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.002);
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.003);
        BigDecimal trailingStopThreshold = BigDecimal.valueOf(0.002);

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
                        .positionSize(activeTrade.getEntryExecutedQty())
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

    public void vwapShortTradeAction(MarketData marketData, FeatureStore featureStore,
                                     BigDecimal accountBalance, BigDecimal riskPercentage,
                                     Users user, String asset) {
        String tradePlan = "vWapShort";
        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        BigDecimal closePrice = marketData.getClosePrice();

        // Fetch Active Trade (if any)
        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlanAndAction(
                user.getId(), asset, "1", tradePlan, "SHORT");

        TradeDecision decision = vwapShortTradeDecision(closePrice, featureStore, accountBalance, riskPercentage,
                marketData.getLowPrice(), activeTradeOpt, asset);

        // Execute Trade Action
        if ("SELL".equals(decision.getAction())) {
            tradeUtil.openShortMarketOrder(user, asset, decision, tradePlan);
        } else if ("BUY".equals(decision.getAction())) {
            tradeUtil.closeShortMarketOrder(user, activeTradeOpt, marketData, asset);
        } else {
            log.info("⏳ HOLD: No trade action needed for {} at {}", asset, closePrice);
        }
    }

    public TradeDecision vwapShortTradeDecision(BigDecimal closePrice, FeatureStore featureStore,
                                                BigDecimal accountBalance, BigDecimal riskPercentage,
                                                BigDecimal lastLowestPrice, Optional<Trades> activeTradeOpt,
                                                String asset) {

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.002);
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.003);
        BigDecimal trailingStopThreshold = BigDecimal.valueOf(0.002);

        // Compute Stop-Loss and Take-Profit Levels
        BigDecimal stopLossPrice = closePrice.multiply(BigDecimal.ONE.add(stopLossThreshold));
        BigDecimal takeProfitPrice = closePrice.multiply(BigDecimal.ONE.subtract(takeProfitThreshold));

        // Compute Position Size based on Risk
        BigDecimal riskAmount = accountBalance.multiply(riskPercentage)
                .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
        BigDecimal positionSize = riskAmount.divide(stopLossPrice.subtract(closePrice), RoundingMode.HALF_UP);

        // Check if there is an active trade
        if (activeTradeOpt.isPresent()) {
            Trades activeTrade = activeTradeOpt.get();

            // Adjust Trailing Stop if Price Decreases
            if (closePrice.compareTo(lastLowestPrice) < 0) {
                lastLowestPrice = closePrice;
                BigDecimal newStopLoss = lastLowestPrice.multiply(BigDecimal.ONE.add(trailingStopThreshold));
                activeTrade.setStopLossPrice(newStopLoss);
                tradesRepository.save(activeTrade);
                log.info("🔄 Trailing Stop Updated for {} to {}", asset, newStopLoss);
            }

            // Check if Stop-Loss or Take-Profit is Hit
            if (closePrice.compareTo(activeTrade.getStopLossPrice()) >= 0 ||
                    closePrice.compareTo(activeTrade.getTakeProfitPrice()) <= 0) {
                return TradeDecision.builder()
                        .action("BUY")
                        .positionSize(activeTrade.getEntryExecutedQty())
                        .stopLossPrice(activeTrade.getStopLossPrice())
                        .takeProfitPrice(activeTrade.getTakeProfitPrice())
                        .build();
            }
        } else {
            // Selling Condition (Only if there's no active trade)
            if (closePrice.compareTo(featureStore.getVwap()) < 0) {
                log.info("✅ SELL signal detected for {}", asset);
                return TradeDecision.builder()
                        .action("SELL")
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



    public void trendFollwoingLongTradeAction(MarketData marketData, FeatureStore featureStore,
                                              BigDecimal accountBalance, BigDecimal riskPercentage,
                                              Users user, String asset) {
        String tradePlan = "trendLong";
        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        Optional<Portfolio> usdAsset = portfolioRepository.findByUserIdAndAsset(user.getId(), "USDT");

        if (!usdAsset.isPresent() || usdAsset.get().getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            log.info("No USDT Asset Found, cannot making trade action.");
        }

        BigDecimal closePrice = marketData.getClosePrice();

        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlanAndAction(user.getId(), asset, "1", tradePlan, "LONG");

        TradeDecision decision = trendFollowingLongTradeDecision(marketData, featureStore, accountBalance, riskPercentage,
                activeTradeOpt, asset);

        if ("BUY".equals(decision.getAction())) {
            tradeUtil.openLongMarketOrder(user, asset, decision, tradePlan);
        } else if ("SELL".equals(decision.getAction())) {
            tradeUtil.closeLongMarketOrder(user,activeTradeOpt, marketData, asset);
        } else {
            log.info("⏳ HOLD: No Long trade action needed for {} at {}", asset, closePrice);
        }
    }

    /**
     * Determines whether to BUY, SELL, or HOLD based on moving averages.
     */
    private TradeDecision trendFollowingLongTradeDecision(MarketData marketData, FeatureStore featureStore,
                                                          BigDecimal accountBalance, BigDecimal riskPercentage,
                                                          Optional<Trades> activeTradeOpt, String asset) {

        BigDecimal closePrice = marketData.getClosePrice();
        BigDecimal ema9 = featureStore.getEma9();
        BigDecimal ema21 = featureStore.getEma21();
        BigDecimal sma50 = featureStore.getSma50();

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.002);
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.003);
        BigDecimal trailingStopThreshold = BigDecimal.valueOf(0.002);

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
                        .positionSize(activeTrade.getEntryExecutedQty())
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


    public void trendFollowingShortTradeAction(MarketData marketData, FeatureStore featureStore,
                                               BigDecimal accountBalance, BigDecimal riskPercentage,
                                               Users user, String asset) {
        String tradePlan = "trendShort";
        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        Optional<Portfolio> assetPosition = portfolioRepository.findByUserIdAndAsset(user.getId(), "BTC");
        if (assetPosition.isEmpty() || assetPosition.get().getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            log.info("No asset balance found, cannot make trade action.");
            return;
        }

        BigDecimal closePrice = marketData.getClosePrice();
        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlanAndAction(
                user.getId(), asset, "1", tradePlan, "SHORT");

        TradeDecision decision = trendFollowingShortTradeDecision(marketData, featureStore, accountBalance, riskPercentage,
                activeTradeOpt, asset);

        if ("SELL".equals(decision.getAction())) {
            tradeUtil.openShortMarketOrder(user, asset, decision, tradePlan);
        } else if ("BUY".equals(decision.getAction())) {
            tradeUtil.closeShortMarketOrder(user, activeTradeOpt, marketData, asset);
        } else {
            log.info("⏳ HOLD: No Short trade action needed for {} at {}", asset, closePrice);
        }
    }



    public TradeDecision trendFollowingShortTradeDecision(MarketData marketData, FeatureStore featureStore,
                                                          BigDecimal accountBalance, BigDecimal riskPercentage,
                                                          Optional<Trades> activeTradeOpt, String asset) {

        BigDecimal closePrice = marketData.getClosePrice();
        BigDecimal ema9 = featureStore.getEma9();
        BigDecimal ema21 = featureStore.getEma21();
        BigDecimal sma50 = featureStore.getSma50();

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.002);
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.003);
        BigDecimal trailingStopThreshold = BigDecimal.valueOf(0.002);

        // Compute Stop-Loss and Take-Profit Levels
        BigDecimal stopLossPrice = closePrice.multiply(BigDecimal.ONE.add(stopLossThreshold));
        BigDecimal takeProfitPrice = closePrice.multiply(BigDecimal.ONE.subtract(takeProfitThreshold));

        // Compute Position Size based on Risk
        BigDecimal riskAmount = accountBalance.multiply(riskPercentage)
                .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
        BigDecimal positionSize = riskAmount.divide(stopLossPrice.subtract(closePrice), RoundingMode.HALF_UP);

        // Check if there is an active trade
        if (activeTradeOpt.isPresent()) {
            Trades activeTrade = activeTradeOpt.get();

            // Adjust Trailing Stop if Price Decreases
            if (closePrice.compareTo(activeTrade.getTakeProfitPrice()) < 0) {
                BigDecimal newStopLoss = closePrice.multiply(BigDecimal.ONE.add(trailingStopThreshold));
                activeTrade.setStopLossPrice(newStopLoss);
                tradesRepository.save(activeTrade);
                log.info("🔄 Trailing Stop Updated for {} to {}", asset, newStopLoss);
            }

            // Check if Stop-Loss or Take-Profit is Hit
            if (closePrice.compareTo(activeTrade.getStopLossPrice()) >= 0 ||
                    closePrice.compareTo(activeTrade.getTakeProfitPrice()) <= 0) {
                return TradeDecision.builder()
                        .action("BUY")
                        .positionSize(activeTrade.getEntryExecutedQty())
                        .stopLossPrice(activeTrade.getStopLossPrice())
                        .takeProfitPrice(activeTrade.getTakeProfitPrice())
                        .build();
            }
        } else {
            // Selling Condition (Only if there's no active trade)
            if (ema9.compareTo(ema21) < 0 && closePrice.compareTo(sma50) < 0) {
                log.info("✅ SELL signal detected for {} size {}", asset, positionSize);
                return TradeDecision.builder()
                        .action("SELL")
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
