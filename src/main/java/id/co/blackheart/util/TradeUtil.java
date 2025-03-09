package id.co.blackheart.util;


import id.co.blackheart.dto.*;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.TradeExecutionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@AllArgsConstructor
@Slf4j
public class TradeUtil {
    TradesRepository tradesRepository;
    TradeExecutionService tradeExecutionService;


    public void openLongMarketOrder(Users user, String asset, TradeDecision decision, String tradePlan) {
        try {
            MarketOrderRequest marketOrderRequest = MarketOrderRequest.builder()
                    .symbol("BTC_USDT")
                    .side(0)
                    .amount(BigDecimal.valueOf(7))
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

    public void closeLongMarketOrder(Users user, Optional<Trades> activeTradeOpt, MarketData marketData, String asset) {
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

                tradesRepository.save(trade);
                log.info("❌ Long order executed. Long Trade closed for {} at {}", asset, orderDetailResponse.getExecutedPrice());
            } catch (Exception e) {
                log.error("❌ Error closing market order: ", e);
            }
        });
    }

    public void openShortMarketOrder(Users user, String asset, TradeDecision decision, String tradePlan) {
        try {
            MarketOrderRequest marketOrderRequest = MarketOrderRequest.builder()
                    .symbol("BTC_USDT")
                    .side(1)
                    .amount(new BigDecimal("0.00008"))
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

    public void closeShortMarketOrder(Users user, Optional<Trades> activeTradeOpt, MarketData marketData, String asset) {
        activeTradeOpt.ifPresent(trade -> {
            try {
                MarketOrderRequest marketOrderRequest = MarketOrderRequest.builder()
                        .symbol("BTC_USDT")
                        .side(0)
                        .amount(trade.getEntryExecutedQty())
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

                trade.setExitOrderId(marketOrderResponse.getOrderId());
                trade.setExitExecutedQuoteQty(new BigDecimal(orderDetailResponse.getExecutedQuoteQty()));
                trade.setExitExecutedQty(new BigDecimal(orderDetailResponse.getExecutedQty()));
                trade.setIsActive("0");
                trade.setExitTime(LocalDateTime.now());
                trade.setExitPrice(marketData.getClosePrice());

                tradesRepository.save(trade);
                log.info("❌ Short order executed. Short Trade closed for {} at {}", asset, orderDetailResponse.getExecutedPrice());
            } catch (Exception e) {
                log.error("❌ Error closing market order: ", e);
            }
        });
    }

}
