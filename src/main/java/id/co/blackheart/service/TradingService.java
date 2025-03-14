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
import java.util.Optional;

@Service
@Slf4j
@AllArgsConstructor
public class TradingService {
    private final TradesRepository tradesRepository;
    private final PortfolioRepository portfolioRepository;
    private final TradeExecutionService tradeExecutionService;
    private final TradeUtil tradeUtil;


    public void cnnTransformeLongTradeAction(MarketData marketData, FeatureStore featureStore,
                                        BigDecimal accountBalance, BigDecimal riskPercentage,
                                        Users user, String asset) {
        String tradePlan = "cnn_transformer_long";

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

        TradeDecision decision = cnnTransformerLongTradeDecision(marketData, featureStore, accountBalance, riskPercentage, activeTradeOpt, asset);

        if ("BUY".equals(decision.getAction())) {
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
    private TradeDecision cnnTransformerLongTradeDecision(MarketData marketData, FeatureStore featureStore,
                                                          BigDecimal accountBalance, BigDecimal riskPercentage,
                                                          Optional<Trades> activeTradeOpt, String asset) {

        BigDecimal closePrice = marketData.getClosePrice();
        String signal = featureStore.getSignal();
        BigDecimal confidence = featureStore.getConfidence();

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.005); // 0.5% Stop Loss
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.01); // 1% Take Profit

        // Compute Stop-Loss and Take-Profit Levels
        BigDecimal stopLossPrice = closePrice.subtract(closePrice.multiply(stopLossThreshold));
        BigDecimal takeProfitPrice = closePrice.add(closePrice.multiply(takeProfitThreshold));

        // Compute Position Size based on Risk
        BigDecimal riskAmount = accountBalance.multiply(riskPercentage)
                .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
        BigDecimal positionSize = riskAmount.divide(closePrice.subtract(stopLossPrice), RoundingMode.HALF_UP);

        // Check if there is an active trade
        if (activeTradeOpt.isPresent()) {
            Trades activeTrade = activeTradeOpt.get();

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
            log.info("signal {} : confidence {} ", signal, confidence );
            if (signal.equals("BUY") && confidence.compareTo(BigDecimal.valueOf(0.6)) >= 0) {
                log.info("✅ BUY signal detected for {} with confidence {}", asset, confidence);
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

    public void cnnTransformerShortTradeAction(MarketData marketData, FeatureStore featureStore,
                                     BigDecimal accountBalance, BigDecimal riskPercentage,
                                     Users user, String asset) {
        String tradePlan = "cnn_transformer_short";
        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        BigDecimal closePrice = marketData.getClosePrice();

        // Fetch Active Trade (if any)
        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlanAndAction(
                user.getId(), asset, "1", tradePlan, "SHORT");

        TradeDecision decision = cnnTransformerShortTradeDecision(closePrice, featureStore, accountBalance, riskPercentage,
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

    public TradeDecision cnnTransformerShortTradeDecision(BigDecimal closePrice, FeatureStore featureStore,
                                                          BigDecimal accountBalance, BigDecimal riskPercentage,
                                                          BigDecimal lastLowestPrice, Optional<Trades> activeTradeOpt,
                                                          String asset) {

        // Risk Parameters
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.005); // 0.5% Stop Loss
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.01); // 1% Take Profit

        // Compute Stop-Loss and Take-Profit Levels
        BigDecimal stopLossPrice = closePrice.add(closePrice.multiply(stopLossThreshold));
        BigDecimal takeProfitPrice = closePrice.subtract(closePrice.multiply(takeProfitThreshold));

        // Compute Position Size based on Risk
        BigDecimal riskAmount = accountBalance.multiply(riskPercentage)
                .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
        BigDecimal positionSize = riskAmount.divide(stopLossPrice.subtract(closePrice), RoundingMode.HALF_UP);

        // Check if there is an active trade
        if (activeTradeOpt.isPresent()) {
            Trades activeTrade = activeTradeOpt.get();

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
            if (featureStore.getSignal().equals("SELL") && featureStore.getConfidence().compareTo(BigDecimal.valueOf(0.6)) >= 0) {
                log.info("✅ SELL signal detected for {} with confidence {}", asset, featureStore.getConfidence());
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

    /**
     * Determines trade action based on market data and strategy.
     */
    public void cnnTransformerLongShortTradeAction(MarketData marketData, FeatureStore featureStore,
                                                   BigDecimal accountBalance, BigDecimal riskPercentage,
                                                   Users user, String asset) {
        String tradePlan = "cnn_transformer_long_short";

        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        BigDecimal closePrice = marketData.getClosePrice();

        // Fetch Active Trade (if any)
        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlan(
                user.getId(), asset, "1", tradePlan);

        // Determine Trade Decision
        TradeDecision decision = cnnTransformerLongShortTradeDecision(
                closePrice, featureStore, accountBalance, riskPercentage, marketData.getHighPrice(), activeTradeOpt, asset);

        // Execute Trade Action
        executeTradeAction(user, asset, decision, tradePlan, activeTradeOpt, featureStore, marketData);
    }

    /**
     * Executes the trade action based on the trade decision.
     */
    private void executeTradeAction(Users user, String asset, TradeDecision decision,
                                    String tradePlan, Optional<Trades> activeTradeOpt, FeatureStore featureStore, MarketData marketData) {
        String signal = featureStore.getSignal();

        switch (decision.getAction()) {
            case "BUY":
                if ("BUY".equals(signal)) {
                    tradeUtil.openLongMarketOrder(user, asset, decision, tradePlan);
                } else {
                    tradeUtil.closeShortMarketOrder(user, activeTradeOpt, marketData, asset);
                }
                break;
            case "SELL":
                if ("SELL".equals(signal)) {
                    tradeUtil.openShortMarketOrder(user, asset, decision, tradePlan);
                } else {
                    tradeUtil.closeLongMarketOrder(user, activeTradeOpt, marketData, asset);
                }
                break;
            default:
                log.info("⏳ HOLD: No trade action needed for {} at {}", asset, decision);
                break;
        }
    }

    /**
     * Determines whether to BUY, SELL, or HOLD.
     */
    private TradeDecision cnnTransformerLongShortTradeDecision(BigDecimal closePrice, FeatureStore featureStore,
                                                               BigDecimal accountBalance, BigDecimal riskPercentage,
                                                               BigDecimal lastHighestPrice, Optional<Trades> activeTradeOpt,
                                                               String asset) {
        // Risk Parameters
        final BigDecimal STOP_LOSS_THRESHOLD = BigDecimal.valueOf(0.005);  // 0.5% Stop Loss
        final BigDecimal TAKE_PROFIT_THRESHOLD = BigDecimal.valueOf(0.01); // 1% Take Profit

        // Compute Stop-Loss and Take-Profit Levels
        BigDecimal stopLossPriceShort = closePrice.multiply(BigDecimal.ONE.add(STOP_LOSS_THRESHOLD));
        BigDecimal takeProfitPriceShort = closePrice.multiply(BigDecimal.ONE.subtract(TAKE_PROFIT_THRESHOLD));
        BigDecimal stopLossPriceLong = closePrice.multiply(BigDecimal.ONE.subtract(STOP_LOSS_THRESHOLD));
        BigDecimal takeProfitPriceLong = closePrice.multiply(BigDecimal.ONE.add(TAKE_PROFIT_THRESHOLD));

        // If an active trade exists, check for stop-loss/take-profit conditions
        if (activeTradeOpt.isPresent()) {
            Trades activeTrade = activeTradeOpt.get();

            boolean stopLossHit = closePrice.compareTo(activeTrade.getStopLossPrice()) <= 0;
            boolean takeProfitHit = closePrice.compareTo(activeTrade.getTakeProfitPrice()) >= 0;

            if (activeTrade.getAction().equals("BUY") && (stopLossHit || takeProfitHit)) {
                return createTradeDecision("SELL", activeTrade);
            }
            if (activeTrade.getAction().equals("SELL") && (!stopLossHit || !takeProfitHit)) {
                return createTradeDecision("BUY", activeTrade);
            }
        } else {
            // No Active Trade, Look for New Entry Signals
            if (shouldEnterTrade(featureStore)) {
                String action = featureStore.getSignal().equals("BUY") ? "BUY" : "SELL";
                BigDecimal stopLoss = action.equals("BUY") ? stopLossPriceLong : stopLossPriceShort;
                BigDecimal takeProfit = action.equals("BUY") ? takeProfitPriceLong : takeProfitPriceShort;

                log.info("✅ {} signal detected for {} with confidence {}", action, asset, featureStore.getConfidence());
                return createTradeDecision(action, BigDecimal.ONE, stopLoss, takeProfit);
            }
        }

        return createTradeDecision("HOLD", BigDecimal.ZERO, null, null);
    }

    /**
     * Checks if trade conditions are met based on confidence level.
     */
    private boolean shouldEnterTrade(FeatureStore featureStore) {
        return featureStore.getConfidence().compareTo(BigDecimal.valueOf(0.6)) >= 0;
    }

    /**
     * Helper method to create a TradeDecision object.
     */
    private TradeDecision createTradeDecision(String action, Trades trade) {
        return TradeDecision.builder()
                .action(action)
                .positionSize(trade.getEntryExecutedQty())
                .stopLossPrice(trade.getStopLossPrice())
                .takeProfitPrice(trade.getTakeProfitPrice())
                .build();
    }

    /**
     * Overloaded method for new trade entries.
     */
    private TradeDecision createTradeDecision(String action, BigDecimal positionSize, BigDecimal stopLoss, BigDecimal takeProfit) {
        return TradeDecision.builder()
                .action(action)
                .positionSize(positionSize)
                .stopLossPrice(stopLoss)
                .takeProfitPrice(takeProfit)
                .build();
    }





}
