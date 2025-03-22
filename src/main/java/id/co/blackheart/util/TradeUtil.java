package id.co.blackheart.util;


import id.co.blackheart.client.BinanceClientService;
import id.co.blackheart.dto.*;
import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.request.MarketOrderRequest;
import id.co.blackheart.dto.request.OrderDetailRequest;
import id.co.blackheart.dto.response.*;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.TradeExecutionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@AllArgsConstructor
@Slf4j
public class TradeUtil {
    private final BinanceClientService binanceClientService;
    TradesRepository tradesRepository;
    TradeExecutionService tradeExecutionService;

    public enum TradeType {
        LONG, SHORT
    }


    public void binanceOpenLongMarketOrder(Users user, String asset, TradeDecision decision, String tradePlan, BigDecimal tradeAmount) {
        try {
            log.info("trade amount : " + tradeAmount);

            // 1. Place market order (ensure "FULL" response is set at API call level in your nodejs service)
            BinanceOrderRequest binanceOrderRequest = BinanceOrderRequest.builder()
                    .symbol("BTCUSDT")
                    .side("BUY")
                    .amount(tradeAmount)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            BinanceOrderResponse binanceOrderResponse = tradeExecutionService.binanceMarketOrder(binanceOrderRequest);

            // 2. Calculate weighted average price & total commission from fills[]
            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal totalFee = BigDecimal.ZERO;

            for (BinanceOrderFill fill : binanceOrderResponse.getFills()) {
                BigDecimal qty = new BigDecimal(fill.getQty());
                BigDecimal price = new BigDecimal(fill.getPrice());
                BigDecimal commission = new BigDecimal(fill.getCommission());

                totalQty = totalQty.add(qty);
                totalCost = totalCost.add(price.multiply(qty));
                totalFee = totalFee.add(commission);
            }

            BigDecimal avgEntryPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);

            // 3. Save to DB
            Trades newTrade = new Trades();
            newTrade.setUserId(user.getId());
            newTrade.setAsset(asset);
            newTrade.setTradePlan(tradePlan);
            newTrade.setEntryOrderId(binanceOrderResponse.getOrderId());
            newTrade.setAction("LONG");
            newTrade.setEntryPrice(avgEntryPrice);
            newTrade.setEntryExecutedQty(totalQty);
            newTrade.setEntryExecutedQuoteQty(totalCost);
            newTrade.setEntryFee(totalFee);
            newTrade.setFeeCurrency(binanceOrderResponse.getFills().getFirst().getCommissionAsset());
            newTrade.setStopLossPrice(decision.getStopLossPrice());
            newTrade.setTakeProfitPrice(decision.getTakeProfitPrice());
            newTrade.setIsActive("1");
            newTrade.setEntryTime(LocalDateTime.now());

            tradesRepository.save(newTrade);

            log.info("✅ Long order placed for {} at weighted average price: {}", asset, avgEntryPrice);

        } catch (Exception e) {
            log.error("❌ Error placing market order: ", e);
        }
    }


    public void binanceCloseLongMarketOrder(Users user, Optional<Trades> activeTradeOpt, MarketData marketData, String asset) {
        activeTradeOpt.ifPresent(trade -> {
            try {
                // 1. Send Market Sell Order
                BinanceOrderRequest binanceOrderRequest = BinanceOrderRequest.builder()
                        .symbol("BTCUSDT")
                        .side("SELL")
                        .amount(trade.getEntryExecutedQty()) // Sell same qty as entry
                        .apiKey(user.getApiKey())
                        .apiSecret(user.getApiSecret())
                        .build();

                BinanceOrderResponse binanceOrderResponse = tradeExecutionService.binanceMarketOrder(binanceOrderRequest);

                // 2. Calculate weighted average exit price from fills
                BigDecimal totalQty = BigDecimal.ZERO;
                BigDecimal totalCost = BigDecimal.ZERO;
                BigDecimal totalFee = BigDecimal.ZERO;

                for (BinanceOrderFill fill : binanceOrderResponse.getFills()) {
                    BigDecimal qty = new BigDecimal(fill.getQty());
                    BigDecimal price = new BigDecimal(fill.getPrice());
                    BigDecimal commission = new BigDecimal(fill.getCommission());

                    totalQty = totalQty.add(qty);
                    totalCost = totalCost.add(price.multiply(qty));
                    totalFee = totalFee.add(commission);
                }

                BigDecimal avgExitPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);

                // 3. Close trade record
                trade.setExitOrderId(binanceOrderResponse.getOrderId());
                trade.setExitExecutedQuoteQty(totalCost);
                trade.setExitExecutedQty(totalQty);
                trade.setExitFee(totalFee);
                trade.setIsActive("0");
                trade.setExitTime(LocalDateTime.now());
                trade.setExitPrice(avgExitPrice);
                trade.setPlAmount(calculatePLAmount(trade.getEntryPrice(), avgExitPrice, trade.getEntryExecutedQty(), TradeType.LONG));
                trade.setPlPercent(calculatePLPercentage(trade.getEntryPrice(), avgExitPrice, TradeType.LONG));

                tradesRepository.save(trade);

                log.info("✅ Long position closed for {} at avg price: {}", asset, avgExitPrice);

            } catch (Exception e) {
                log.error("❌ Error closing market order: ", e);
            }
        });
    }



    public void openLongMarketOrder(Users user, String asset, TradeDecision decision, String tradePlan, BigDecimal tradeAmount) {
        try {
            log.info("trade amount : " +  tradeAmount);
            MarketOrderRequest marketOrderRequest = MarketOrderRequest.builder()
                    .symbol("BTC_USDT")
                    .side(0)
                    .amount(tradeAmount)
                    .isQuoteQty(true)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            MarketOrderResponse marketOrderResponse = tradeExecutionService.placeMarketOrder(marketOrderRequest);

            OrderDetailRequest orderDetailRequest = OrderDetailRequest.builder()
                    .orderId(String.valueOf(marketOrderResponse.getOrderId()))
                    .recvWindow(5000)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            OrderDetailResponse orderDetailResponse = tradeExecutionService.getOrderDetail(orderDetailRequest);

            Trades newTrade = new Trades();
            newTrade.setUserId(user.getId());
            newTrade.setAsset(asset);
            newTrade.setTradePlan(tradePlan);
            newTrade.setEntryOrderId(marketOrderResponse.getOrderId());
            newTrade.setAction("LONG");
            newTrade.setEntryPrice(new BigDecimal(orderDetailResponse.getExecutedPrice()));
            newTrade.setEntryExecutedQty(new BigDecimal(orderDetailResponse.getExecutedQty()));
            newTrade.setEntryExecutedQuoteQty(new BigDecimal(orderDetailResponse.getExecutedQuoteQty()));
            newTrade.setStopLossPrice(decision.getStopLossPrice());
            newTrade.setTakeProfitPrice(decision.getTakeProfitPrice());
            newTrade.setIsActive("1");
            newTrade.setEntryTime(LocalDateTime.now());

            tradesRepository.save(newTrade);

            log.info("✅ Long order placed for {} at {}", asset, orderDetailResponse.getExecutedPrice());
        } catch (Exception e) {
            log.error("❌ Error placing market order: ", e);
        }
    }

    public void tokocryptoCloseLongMarketOrder(Users user, Optional<Trades> activeTradeOpt, MarketData marketData, String asset) {
        activeTradeOpt.ifPresent(trade -> {
            try {
                MarketOrderRequest marketOrderRequest = MarketOrderRequest.builder()
                        .symbol("BTC_USDT")
                        .side(1)
                        .amount(trade.getEntryExecutedQty())
                        .isQuoteQty(false)
                        .apiKey(user.getApiKey())
                        .apiSecret(user.getApiSecret())
                        .build();

                MarketOrderResponse marketOrderResponse = tradeExecutionService.placeMarketOrder(marketOrderRequest);

                OrderDetailRequest orderDetailRequest = OrderDetailRequest.builder()
                        .orderId(String.valueOf(marketOrderResponse.getOrderId()))
                        .recvWindow(5000)
                        .apiKey(user.getApiKey())
                        .apiSecret(user.getApiSecret())
                        .build();

                OrderDetailResponse orderDetailResponse = tradeExecutionService.getOrderDetail(orderDetailRequest);

                trade.setExitOrderId(marketOrderResponse.getOrderId());
                trade.setExitExecutedQuoteQty(new BigDecimal(orderDetailResponse.getExecutedQuoteQty()));
                trade.setExitExecutedQty(new BigDecimal(orderDetailResponse.getExecutedQty()));
                trade.setIsActive("0");
                trade.setExitTime(LocalDateTime.now());
                trade.setExitPrice(marketData.getClosePrice());
                trade.setPlAmount(calculatePLAmount(trade.getEntryPrice(), trade.getExitPrice(), trade.getEntryExecutedQty(), TradeType.LONG));
                trade.setPlPercent(calculatePLPercentage(trade.getEntryPrice(), trade.getExitPrice() ,TradeType.LONG));

                tradesRepository.save(trade);
                log.info("❌ Long order executed. Long Trade closed for {} at {}", asset, orderDetailResponse.getExecutedPrice());
            } catch (Exception e) {
                log.error("❌ Error closing market order: ", e);
            }
        });
    }

    public void binanceOpenShortMarketOrder(Users user, String asset, TradeDecision decision, String tradePlan, BigDecimal tradeAmount) {
        try {
            BinanceOrderRequest binanceOrderRequest = BinanceOrderRequest.builder()
                    .symbol("BTCUSDT")
                    .side("SELL")
                    .amount(tradeAmount)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            BinanceOrderResponse binanceOrderResponse = tradeExecutionService.binanceMarketOrder(binanceOrderRequest);

            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal totalFee = BigDecimal.ZERO;

            for (BinanceOrderFill fill : binanceOrderResponse.getFills()) {
                BigDecimal qty = new BigDecimal(fill.getQty());
                BigDecimal price = new BigDecimal(fill.getPrice());
                BigDecimal commission = new BigDecimal(fill.getCommission());
                totalQty = totalQty.add(qty);
                totalCost = totalCost.add(price.multiply(qty));
                totalFee = totalFee.add(commission);
            }

            BigDecimal avgEntryPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);

            // 3. Save trade record
            Trades newTrade = new Trades();
            newTrade.setUserId(user.getId());
            newTrade.setAsset(asset);
            newTrade.setTradePlan(tradePlan);
            newTrade.setEntryOrderId(binanceOrderResponse.getOrderId());
            newTrade.setAction("SHORT");
            newTrade.setEntryPrice(avgEntryPrice);
            newTrade.setEntryExecutedQty(totalQty);
            newTrade.setEntryExecutedQuoteQty(totalCost);
            newTrade.setFeeCurrency(binanceOrderResponse.getFills().getFirst().getCommissionAsset());
            newTrade.setEntryFee(totalFee);
            newTrade.setStopLossPrice(decision.getStopLossPrice());
            newTrade.setTakeProfitPrice(decision.getTakeProfitPrice());
            newTrade.setIsActive("1");
            newTrade.setEntryTime(LocalDateTime.now());

            tradesRepository.save(newTrade);
            log.info("✅ Short order placed for {} at avg price: {}", asset, avgEntryPrice);
        } catch (Exception e) {
            log.error("❌ Error placing short market order: ", e);
        }
    }

    public void binanceCloseShortMarketOrder(Users user, Optional<Trades> activeTradeOpt, MarketData marketData, String asset) {
        if (user == null) {
            log.info("User Data is Null!");
            return;
        }

        activeTradeOpt.ifPresent(trade -> {
            try {
                double currentBtcPrice = binanceClientService.getCurrentBtcPrice();
                BigDecimal btcAmount = trade.getEntryExecutedQty();

                BigDecimal usdtAmountWithBuffer = btcAmount
                        .multiply(BigDecimal.valueOf(currentBtcPrice))
                        .multiply(new BigDecimal("1.01"))
                        .setScale(2, RoundingMode.UP);

                BinanceOrderRequest binanceOrderRequest = BinanceOrderRequest.builder()
                        .symbol("BTCUSDT")
                        .side("BUY") // Buying back to close short
                        .amount(usdtAmountWithBuffer)
                        .apiKey(user.getApiKey())
                        .apiSecret(user.getApiSecret())
                        .build();

                BinanceOrderResponse binanceOrderResponse = tradeExecutionService.binanceMarketOrder(binanceOrderRequest);

                // 2. Weighted average from fills
                BigDecimal totalQty = BigDecimal.ZERO;
                BigDecimal totalCost = BigDecimal.ZERO;
                BigDecimal totalFee = BigDecimal.ZERO;


                for (BinanceOrderFill fill : binanceOrderResponse.getFills()) {
                    BigDecimal qty = new BigDecimal(fill.getQty());
                    BigDecimal price = new BigDecimal(fill.getPrice());
                    BigDecimal commission = new BigDecimal(fill.getCommission());
                    totalQty = totalQty.add(qty);
                    totalCost = totalCost.add(price.multiply(qty));
                    totalFee = totalFee.add(commission);
                }

                BigDecimal avgExitPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);

                // 3. Close trade record
                trade.setExitOrderId(binanceOrderResponse.getOrderId());
                trade.setExitExecutedQuoteQty(totalCost);
                trade.setExitExecutedQty(totalQty);
                trade.setExitFee(totalFee);
                trade.setIsActive("0");
                trade.setExitTime(LocalDateTime.now());
                trade.setExitPrice(avgExitPrice);
                trade.setPlAmount(calculatePLAmount(trade.getEntryPrice(), avgExitPrice, trade.getEntryExecutedQty(), TradeType.SHORT));
                trade.setPlPercent(calculatePLPercentage(trade.getEntryPrice(), avgExitPrice, TradeType.SHORT));

                tradesRepository.save(trade);

                log.info("✅ Short position closed for {} at avg price: {}", asset, avgExitPrice);

            } catch (Exception e) {
                log.error("❌ Error closing short market order: ", e);
            }
        });
    }



    public void openShortMarketOrder(Users user, String asset, TradeDecision decision, String tradePlan, BigDecimal tradeAmount) {
        try {
            MarketOrderRequest marketOrderRequest = MarketOrderRequest.builder()
                    .symbol("BTC_USDT")
                    .side(1)
                    .amount(tradeAmount)
                    .isQuoteQty(false)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            MarketOrderResponse marketOrderResponse = tradeExecutionService.placeMarketOrder(marketOrderRequest);

            OrderDetailRequest orderDetailRequest = OrderDetailRequest.builder()
                    .orderId(String.valueOf(marketOrderResponse.getOrderId()))
                    .recvWindow(5000)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            OrderDetailResponse orderDetailResponse = tradeExecutionService.getOrderDetail(orderDetailRequest);

            Trades newTrade = new Trades();
            newTrade.setUserId(user.getId());
            newTrade.setAsset(asset);
            newTrade.setTradePlan(tradePlan);
            newTrade.setEntryOrderId(marketOrderResponse.getOrderId());
            newTrade.setAction("SHORT");
            newTrade.setEntryPrice(new BigDecimal(orderDetailResponse.getExecutedPrice()));
            newTrade.setEntryExecutedQty(new BigDecimal(orderDetailResponse.getExecutedQty()));
            newTrade.setEntryExecutedQuoteQty(new BigDecimal(orderDetailResponse.getExecutedQuoteQty()));
            newTrade.setStopLossPrice(decision.getStopLossPrice());
            newTrade.setTakeProfitPrice(decision.getTakeProfitPrice());
            newTrade.setIsActive("1");
            newTrade.setEntryTime(LocalDateTime.now());

            tradesRepository.save(newTrade);

            log.info("✅ Short order placed for {} at {}", asset, orderDetailResponse.getExecutedPrice());
        } catch (Exception e) {
            log.error("❌ Error placing market order: ", e);
        }
    }

    public void tokocryptoCloseShortMarketOrder(Users user, Optional<Trades> activeTradeOpt, MarketData marketData, String asset) {

        if (user == null){
            log.info("User Data is Null!");
            return;
        }
        activeTradeOpt.ifPresent(trade -> {
            try {
                MarketOrderRequest marketOrderRequest = MarketOrderRequest.builder()
                        .symbol("BTC_USDT")
                        .side(0)
                        .amount(trade.getEntryExecutedQty())
                        .isQuoteQty(false)
                        .apiKey(user.getApiKey())
                        .apiSecret(user.getApiSecret())
                        .build();

                MarketOrderResponse marketOrderResponse = tradeExecutionService.placeMarketOrder(marketOrderRequest);

                OrderDetailRequest orderDetailRequest = OrderDetailRequest.builder()
                        .orderId(String.valueOf(marketOrderResponse.getOrderId()))
                        .recvWindow(5000)
                        .apiKey(user.getApiKey())
                        .apiSecret(user.getApiSecret())
                        .build();

                OrderDetailResponse orderDetailResponse = tradeExecutionService.getOrderDetail(orderDetailRequest);

                trade.setExitOrderId(marketOrderResponse.getOrderId());
                trade.setExitExecutedQuoteQty(new BigDecimal(orderDetailResponse.getExecutedQuoteQty()));
                trade.setExitExecutedQty(new BigDecimal(orderDetailResponse.getExecutedQty()));
                trade.setIsActive("0");

                trade.setExitTime(LocalDateTime.now());
                trade.setExitPrice(marketData.getClosePrice());
                trade.setPlAmount(calculatePLAmount(trade.getEntryPrice(), trade.getExitPrice(), trade.getEntryExecutedQty(), TradeType.SHORT));
                trade.setPlPercent(calculatePLPercentage(trade.getEntryPrice(), trade.getExitPrice() ,TradeType.SHORT));

                tradesRepository.save(trade);
                log.info("❌ Short order executed. Short Trade closed for {} at {}", asset, orderDetailResponse.getExecutedPrice());
            } catch (Exception e) {
                log.error("❌ Error closing market order: ", e);
            }
        });
    }

    public static BigDecimal calculatePLAmount(BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal quantity, TradeType tradeType) {
        BigDecimal profitLoss;

        if (tradeType == TradeType.LONG) {
            // Long trade: (exitPrice - entryPrice) * quantity
            profitLoss = exitPrice.subtract(entryPrice);
        } else {
            // Short trade: (entryPrice - exitPrice) * quantity
            profitLoss = entryPrice.subtract(exitPrice);
        }

        return profitLoss.multiply(quantity).setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculatePLPercentage(BigDecimal entryPrice, BigDecimal exitPrice, TradeType tradeType) {
        BigDecimal profitLoss;

        if (tradeType == TradeType.LONG) {
            // Long trade: (exitPrice - entryPrice) / entryPrice * 100
            profitLoss = exitPrice.subtract(entryPrice).divide(entryPrice, 10, RoundingMode.HALF_UP);
        } else {
            // Short trade: (entryPrice - exitPrice) / entryPrice * 100
            profitLoss = entryPrice.subtract(exitPrice).divide(entryPrice, 10, RoundingMode.HALF_UP);
        }

        return profitLoss.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    public TradeDecision createTradeDecision(String action, BigDecimal positionSize, BigDecimal stopLoss, BigDecimal takeProfit) {
        return TradeDecision.builder()
                .action(action)
                .positionSize(positionSize)
                .stopLossPrice(stopLoss)
                .takeProfitPrice(takeProfit)
                .build();
    }

}
