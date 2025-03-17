package id.co.blackheart.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.dto.*;
import id.co.blackheart.model.*;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.repository.UsersRepository;
import id.co.blackheart.util.TradeUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@AllArgsConstructor
public class TradingService {
    private final TradesRepository tradesRepository;
    private final PortfolioRepository portfolioRepository;
    private final TradeUtil tradeUtil;
    private final UsersRepository usersRepository;
    private final PortfolioService portfolioService;


    public void cnnTransformeLongTradeAction(MarketData marketData, FeatureStore featureStore,
                                        BigDecimal accountBalance, BigDecimal riskPercentage,
                                        Users user, String asset) throws JsonProcessingException {
        String tradePlan = "cnn_transformer_long";

        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null.");
            return;
        }


        BigDecimal closePrice = marketData.getClosePrice();

        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlanAndAction(user.getId(), asset, "1", tradePlan, "LONG");

        TradeDecision decision = cnnTransformerLongTradeDecision(marketData, featureStore, accountBalance, riskPercentage, activeTradeOpt, asset);

        if ("BUY".equals(decision.getAction())) {
            log.info("✅ {} signal detected for {} with confidence {}", featureStore.getSignal(), asset, featureStore.getConfidence());
            Portfolio usdAsset = portfolioService.updateAndGetAssetBalance("USDT", user, "5000");
            BigDecimal tradeAmount = usdAsset.getBalance().multiply(user.getRiskAmount()).setScale(0, RoundingMode.DOWN);;
            if (tradeAmount.compareTo(BigDecimal.valueOf(7)) <= 0) {
                tradeAmount = BigDecimal.valueOf(7);// minimum Trade Amount
            }
            if (usdAsset.getBalance().compareTo(tradeAmount) < 0) {
                log.info("Insufficient USDT Balance : {}, cannot making trade action.", usdAsset.getBalance());
                return;
            }
            tradeUtil.openLongMarketOrder(user, asset, decision, tradePlan, BigDecimal.ONE);
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
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.01); // 1% Take Profit
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.005); // 0.5% Stop Loss

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
                                     Users user, String asset) throws JsonProcessingException {
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
            Portfolio btcAsset = portfolioService.updateAndGetAssetBalance("BTC", user, "5000");
            BigDecimal tradeAmount = btcAsset.getBalance().multiply(user.getRiskAmount()).setScale(5, RoundingMode.DOWN);
            if (tradeAmount.compareTo(new BigDecimal("0.00008")) <= 0) {
                tradeAmount = new BigDecimal("0.00008");// minimum Trade Amount
            }
            if (btcAsset.getBalance().compareTo(tradeAmount) < 0) {
                log.info("Insufficient Btc Balance : {}, cannot making trade action.", btcAsset.getBalance());
                return;
            }
            tradeUtil.openShortMarketOrder(user, asset, decision, tradePlan, tradeAmount);
        } else if ("BUY".equals(decision.getAction())) {
            tradeUtil.closeShortMarketOrder(user, activeTradeOpt, marketData, asset);
        } else {
            log.info("⏳ HOLD: No trade action needed for {} at {}", asset, closePrice);
        }
    }

    public void activeTradeListener(String asset, BigDecimal closePrice){

        List<Trades> activeTradeList = tradesRepository.findByAssetAndIsActive( asset, "1");

        for (Trades activeTrade : activeTradeList) {

            TradeDecision decision = activeTradeDecision(activeTrade, closePrice);

            Optional<Users> user = usersRepository.findById(activeTrade.getUserId());

            MarketData marketData = new MarketData();
            marketData.setClosePrice(closePrice);

            Optional<Trades> optionalActiveTrade = Optional.ofNullable(activeTrade);
            // Execute Trade Action
            if ("SELL".equals(decision.getAction())) {
                tradeUtil.closeLongMarketOrder(user.orElse(null), optionalActiveTrade, marketData, asset);
            } else if ("BUY".equals(decision.getAction())) {
                tradeUtil.closeShortMarketOrder(user.orElse(null), optionalActiveTrade, marketData, asset);
            }
        }
    }


    public TradeDecision activeTradeDecision(Trades activeTrade, BigDecimal closePrice) {
        boolean stopLossHit = closePrice.compareTo(activeTrade.getStopLossPrice()) <= 0;
        boolean takeProfitHit = closePrice.compareTo(activeTrade.getTakeProfitPrice()) >= 0;
        if (activeTrade.getAction().equals("LONG") && (stopLossHit || takeProfitHit)) {
            return tradeUtil.createTradeDecision("SELL", activeTrade.getEntryExecutedQty(), activeTrade.getStopLossPrice(),activeTrade.getTakeProfitPrice());
        }
        if (activeTrade.getAction().equals("SHORT") && (stopLossHit || takeProfitHit)) {
            return tradeUtil.createTradeDecision("BUY", activeTrade.getEntryExecutedQty(), activeTrade.getStopLossPrice(),activeTrade.getTakeProfitPrice());
        }

        return tradeUtil.createTradeDecision("HOLD", BigDecimal.ZERO, null, null);
    }

    public TradeDecision cnnTransformerShortTradeDecision(BigDecimal closePrice, FeatureStore featureStore,
                                                          BigDecimal accountBalance, BigDecimal riskPercentage,
                                                          BigDecimal lastLowestPrice, Optional<Trades> activeTradeOpt,
                                                          String asset) {

        // Risk Parameters
        BigDecimal takeProfitThreshold = BigDecimal.valueOf(0.012); // 0.2% Take Profit
        BigDecimal stopLossThreshold = BigDecimal.valueOf(0.005); // 0.1% Stop Loss


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
                log.info("✅ {} signal detected for {} with confidence {}", featureStore.getSignal() , asset, featureStore.getConfidence());
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
                                                   Users user, String asset) throws JsonProcessingException {
        String tradePlan = "cnn_transformer_long_short";

        if (marketData == null || featureStore == null) {
            log.warn("❌ Market data or feature store is null. Cannot determine trade action.");
            return;
        }

        // Fetch Active Trade (if any)
        Optional<Trades> activeTradeOpt = tradesRepository.findByUserIdAndAssetAndIsActiveAndTradePlan(
                user.getId(), asset, "1", tradePlan);

        // Determine Trade Decision
        TradeDecision decision = cnnTransformerLongShortTradeDecision(marketData, featureStore, activeTradeOpt, asset);

        // Execute Trade Action
        executeTradeAction(user, asset, decision, tradePlan, activeTradeOpt, featureStore, marketData);
    }

    /**
     * Executes the trade action based on the trade decision.
     */
    private void executeTradeAction(Users user, String asset, TradeDecision decision,
                                    String tradePlan, Optional<Trades> activeTradeOpt, FeatureStore featureStore, MarketData marketData) throws JsonProcessingException {

        switch (decision.getAction()) {
            case "BUY":
                if ("BUY".equals(featureStore.getSignal()) && activeTradeOpt.isEmpty()) {
                    Portfolio usdAsset = portfolioService.updateAndGetAssetBalance("USDT", user, "5000");
                    BigDecimal tradeAmount = usdAsset.getBalance().multiply(user.getRiskAmount()).setScale(0, RoundingMode.DOWN);;
                    if (tradeAmount.compareTo(BigDecimal.valueOf(7)) <= 0) {
                        tradeAmount = BigDecimal.valueOf(7);// minimum Trade Amount
                    }
                    if (usdAsset.getBalance().compareTo(tradeAmount) < 0) {
                        log.info("Insufficient USDT Balance : {}, cannot making trade action.", usdAsset.getBalance());
                        return;
                    }
                    tradeUtil.openLongMarketOrder(user, asset, decision, tradePlan, tradeAmount);
                } else {
                    tradeUtil.closeShortMarketOrder(user, activeTradeOpt, marketData, asset);
                }
                break;
            case "SELL":
                if ("SELL".equals(featureStore.getSignal()) && activeTradeOpt.isEmpty()) {
                    Portfolio btcAsset = portfolioService.updateAndGetAssetBalance("BTC", user, "5000");
                    BigDecimal tradeAmount = btcAsset.getBalance().multiply(user.getRiskAmount()).setScale(5, RoundingMode.DOWN);
                    if (tradeAmount.compareTo(new BigDecimal("0.00008")) <= 0) {
                        tradeAmount = new BigDecimal("0.00008");// minimum Trade Amount
                    }
                    if (btcAsset.getBalance().compareTo(tradeAmount) < 0) {
                        log.info("Insufficient Btc Balance : {}, cannot making trade action.", btcAsset.getBalance());
                        return;
                    }
                    tradeUtil.openShortMarketOrder(user, asset, decision, tradePlan, tradeAmount);
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
    private TradeDecision cnnTransformerLongShortTradeDecision(MarketData marketData, FeatureStore featureStore, Optional<Trades> activeTradeOpt,
                                                               String asset) {
        // Risk Parameters
        final BigDecimal TAKE_PROFIT_THRESHOLD = BigDecimal.valueOf(0.01); // 1% Take Profit
        final BigDecimal STOP_LOSS_THRESHOLD = BigDecimal.valueOf(0.005);  // 0.5% Stop Loss

        // Compute Stop-Loss and Take-Profit Levels
        BigDecimal stopLossPriceShort = marketData.getClosePrice().multiply(BigDecimal.ONE.add(STOP_LOSS_THRESHOLD));
        BigDecimal takeProfitPriceShort = marketData.getClosePrice().multiply(BigDecimal.ONE.subtract(TAKE_PROFIT_THRESHOLD));
        BigDecimal stopLossPriceLong = marketData.getClosePrice().multiply(BigDecimal.ONE.subtract(STOP_LOSS_THRESHOLD));
        BigDecimal takeProfitPriceLong = marketData.getClosePrice().multiply(BigDecimal.ONE.add(TAKE_PROFIT_THRESHOLD));

        // If an active trade exists, check for stop-loss/take-profit conditions
        if (activeTradeOpt.isPresent()) {
            Trades activeTrade = activeTradeOpt.get();
            log.info("✅is Present {} signal detected for {} with confidence {}", featureStore.getSignal(), asset, featureStore.getConfidence());

            boolean stopLossHit = marketData.getClosePrice().compareTo(activeTrade.getStopLossPrice()) <= 0;
            boolean takeProfitHit = marketData.getClosePrice().compareTo(activeTrade.getTakeProfitPrice()) >= 0;

            if (activeTrade.getAction().equals("LONG") && (stopLossHit || takeProfitHit)) {
                return tradeUtil.createTradeDecision("SELL", activeTrade.getEntryExecutedQty(), activeTrade.getStopLossPrice(),activeTrade.getTakeProfitPrice());
            }
            if (activeTrade.getAction().equals("SHORT") && (!stopLossHit || !takeProfitHit)) {
                return tradeUtil.createTradeDecision("BUY", activeTrade.getEntryExecutedQty(), activeTrade.getStopLossPrice(),activeTrade.getTakeProfitPrice());
            }
        } else {
            // No Active Trade, Look for New Entry Signals
            if (featureStore.getConfidence().compareTo(BigDecimal.valueOf(0.6)) >= 0) {
                String signal = featureStore.getSignal();  // Get model prediction (BUY, SELL, or HOLD)
                BigDecimal stopLoss = null;
                BigDecimal takeProfit = null;
                log.info("✅ {} signal detected for {} with confidence {}", featureStore.getSignal(), asset, featureStore.getConfidence());
                if ("BUY".equals(signal)) {
                    stopLoss = stopLossPriceLong;
                    takeProfit = takeProfitPriceLong;
                } else if ("SELL".equals(signal)) {
                    stopLoss = stopLossPriceShort;
                    takeProfit = takeProfitPriceShort;
                } else {
                    log.info("⏳ HOLD signal detected for {}. No action taken.", asset);
                    return tradeUtil.createTradeDecision("HOLD", BigDecimal.ZERO, null, null);
                }

                log.info("✅ {} signal detected for {} with confidence {}", signal, asset, featureStore.getConfidence());
                return tradeUtil.createTradeDecision(signal, BigDecimal.ONE, stopLoss, takeProfit);
            }
        }

        return tradeUtil.createTradeDecision("HOLD", BigDecimal.ZERO, null, null);
    }

}
