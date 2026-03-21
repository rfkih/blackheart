package id.co.blackheart.util;


import id.co.blackheart.dto.*;
import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.response.*;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.tradeexecuition.TradeExecutionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
@AllArgsConstructor
@Slf4j
public class TradeUtil {
    TradesRepository tradesRepository;
    TradeExecutionService tradeExecutionService;

    public enum TradeType {
        LONG, SHORT
    }


    public void binanceOpenLongMarketOrder(
            StrategyContext context,
            TradeDecision decision,
            String tradePlan,
            BigDecimal tradeAmount
    ) {
        try {
            log.info("trade amount : {}", tradeAmount);

            BinanceOrderRequest request = BinanceOrderRequest.builder()
                    .symbol(context.getAsset())
                    .side("BUY")
                    .amount(tradeAmount)
                    .apiKey(context.getUser().getApiKey())
                    .apiSecret(context.getUser().getApiSecret())
                    .build();

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(request);

            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal totalFee = BigDecimal.ZERO;
            String feeCurrency = null;

            if (response.getFills() != null && !response.getFills().isEmpty()) {
                for (BinanceOrderFill fill : response.getFills()) {
                    BigDecimal qty = new BigDecimal(fill.getQty());
                    BigDecimal price = new BigDecimal(fill.getPrice());
                    BigDecimal commission = new BigDecimal(fill.getCommission());

                    totalQty = totalQty.add(qty);
                    totalCost = totalCost.add(price.multiply(qty));
                    totalFee = totalFee.add(commission);

                    if (feeCurrency == null) {
                        feeCurrency = fill.getCommissionAsset();
                    }
                }
            }

            if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Order executed but total filled quantity is zero.");
            }

            BigDecimal avgEntryPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);

            Trades newTrade = Trades.builder()
                    .userId(context.getUser().getUserId())
                    .strategyName(tradePlan)
                    .interval(context.getInterval())
                    .exchange("BINANCE")
                    .asset(context.getAsset())
                    .side("LONG")
                    .status("OPEN")
                    .entryOrderId(response.getOrderId())
                    .entryExecutedQty(totalQty)
                    .entryExecutedQuoteQty(totalCost)
                    .entryFee(totalFee)
                    .entryFeeCurrency(feeCurrency)
                    .entryPrice(avgEntryPrice)
                    .initialStopLossPrice(decision.getStopLossPrice())
                    .currentStopLossPrice(decision.getStopLossPrice())
                    .takeProfitPrice(decision.getTakeProfitPrice())
                    .entryTime(LocalDateTime.now())
                    .userStrategyId(context.getUserStrategyId())
                    .build();

            tradesRepository.save(newTrade);

            log.info("✅ Long order placed for {} at weighted average price: {}", context.getAsset(), avgEntryPrice);

        } catch (Exception e) {
            log.error("❌ Error placing long market order for {}", context.getAsset(), e);
        }
    }


    private Trades validateCloseLongOrderInputs(Users user, Trades trade, String asset) {
        if (user == null) {
            log.info("User data is null!");
            return null;
        }

        if (null == trade) {
            log.info("No active trade found for asset {}", asset);
            return null;
        }

        BigDecimal closeQty = trade.getEntryExecutedQty();

        if (closeQty == null || closeQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Entry executed quantity is invalid for closing long trade.");
        }

        return trade;
    }

    private FillProcessingResult processOrderFills(BinanceOrderResponse response) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        String feeCurrency = null;

        if (response.getFills() != null && !response.getFills().isEmpty()) {
            for (BinanceOrderFill fill : response.getFills()) {
                BigDecimal qty = new BigDecimal(fill.getQty());
                BigDecimal price = new BigDecimal(fill.getPrice());
                BigDecimal commission = new BigDecimal(fill.getCommission());

                totalQty = totalQty.add(qty);
                totalCost = totalCost.add(price.multiply(qty));
                totalFee = totalFee.add(commission);

                if (feeCurrency == null && fill.getCommissionAsset() != null) {
                    feeCurrency = fill.getCommissionAsset();
                }
            }
        }

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0 && response.getExecutedQty() != null) {
            totalQty = new BigDecimal(response.getExecutedQty());
        }

        if (totalCost.compareTo(BigDecimal.ZERO) <= 0 && response.getCummulativeQuoteQty() != null) {
            totalCost = new BigDecimal(response.getCummulativeQuoteQty());
        }

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Executed quantity is zero, cannot close trade.");
        }

        BigDecimal avgExitPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);
        return new FillProcessingResult(totalQty, totalCost, totalFee, feeCurrency, avgExitPrice);
    }

    private static class FillProcessingResult {
        final BigDecimal totalQty;
        final BigDecimal totalCost;
        final BigDecimal totalFee;
        final String feeCurrency;
        final BigDecimal avgExitPrice;

        FillProcessingResult(BigDecimal totalQty, BigDecimal totalCost, BigDecimal totalFee, String feeCurrency, BigDecimal avgExitPrice) {
            this.totalQty = totalQty;
            this.totalCost = totalCost;
            this.totalFee = totalFee;
            this.feeCurrency = feeCurrency;
            this.avgExitPrice = avgExitPrice;
        }
    }

    public void binanceCloseLongMarketOrder(
            Users user,
           Trades activeTradeOpt,
            String asset
    ) {
        Trades trade = validateCloseLongOrderInputs(user, activeTradeOpt, asset);
        if (trade == null) {
            return;
        }

        try {
            BigDecimal closeQty = trade.getEntryExecutedQty();

            BinanceOrderRequest request = BinanceOrderRequest.builder()
                    .symbol(asset)
                    .side("SELL")
                    .amount(closeQty)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(request);
            
            FillProcessingResult result = processOrderFills(response);
            updateTradeWithExitData(trade, response, result);
            tradesRepository.save(trade);

            log.info("✅ Long position closed for {} | orderId={} | avgExitPrice={}",
                    asset, response.getOrderId(), result.avgExitPrice);

        } catch (Exception e) {
            log.error("❌ Error closing long market order for {}", asset, e);
        }
    }

    private void updateTradeWithExitData(Trades trade, BinanceOrderResponse response, FillProcessingResult result) {
        trade.setExitOrderId(response.getOrderId());
        trade.setExitExecutedQty(result.totalQty);
        trade.setExitExecutedQuoteQty(result.totalCost);
        trade.setExitFee(result.totalFee);
        trade.setExitFeeCurrency(result.feeCurrency);
        trade.setExitPrice(result.avgExitPrice);
        trade.setExitTime(LocalDateTime.now());
        trade.setStatus("CLOSED");
        trade.setPlAmount(
                calculatePLAmount(
                        trade.getEntryPrice(),
                        result.avgExitPrice,
                        trade.getEntryExecutedQty(),
                        TradeType.LONG
                )
        );
        trade.setPlPercent(
                calculatePLPercentage(
                        trade.getEntryPrice(),
                        result.avgExitPrice,
                        TradeType.LONG
                )
        );
    }

    public void binanceOpenShortMarketOrder(
            StrategyContext context,
            String asset,
            TradeDecision decision,
            String tradePlan,
            BigDecimal tradeAmount
    ) {
        try {
            BinanceOrderRequest binanceOrderRequest = BinanceOrderRequest.builder()
                    .symbol(context.getAsset())
                    .side("SELL")
                    .amount(tradeAmount)
                    .apiKey(context.getUser().getApiKey())
                    .apiSecret(context.getUser().getApiSecret())
                    .build();

            BinanceOrderResponse binanceOrderResponse =
                    tradeExecutionService.binanceMarketOrder(binanceOrderRequest);

            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal totalFee = BigDecimal.ZERO;
            String feeCurrency = null;

            if (binanceOrderResponse.getFills() != null && !binanceOrderResponse.getFills().isEmpty()) {
                for (BinanceOrderFill fill : binanceOrderResponse.getFills()) {
                    BigDecimal qty = new BigDecimal(fill.getQty());
                    BigDecimal price = new BigDecimal(fill.getPrice());
                    BigDecimal commission = new BigDecimal(fill.getCommission());

                    totalQty = totalQty.add(qty);
                    totalCost = totalCost.add(price.multiply(qty));
                    totalFee = totalFee.add(commission);

                    if (feeCurrency == null) {
                        feeCurrency = fill.getCommissionAsset();
                    }
                }
            }

            if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Order executed but total filled quantity is zero.");
            }

            BigDecimal avgEntryPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);

            Trades newTrade = Trades.builder()
                    .userId(context.getUser().getUserId())
                    .strategyName(tradePlan)
                    .interval(context.getInterval())
                    .exchange("BINANCE")
                    .asset(context.getAsset())
                    .side("SHORT")
                    .status("OPEN")
                    .entryOrderId(binanceOrderResponse.getOrderId())
                    .entryExecutedQty(totalQty)
                    .entryExecutedQuoteQty(totalCost)
                    .entryFee(totalFee)
                    .entryFeeCurrency(feeCurrency)
                    .entryPrice(avgEntryPrice)
                    .initialStopLossPrice(decision.getStopLossPrice())
                    .currentStopLossPrice(decision.getStopLossPrice())
                    .takeProfitPrice(decision.getTakeProfitPrice())
                    .entryTime(LocalDateTime.now())
                    .userStrategyId(context.getUserStrategyId())
                    .build();

            tradesRepository.save(newTrade);

            log.info("✅ Short order placed for {} at avg price: {}", asset, avgEntryPrice);

        } catch (Exception e) {
            log.error("❌ Error placing short market order for asset {}", asset, e);
        }
    }


    public void binanceCloseShortMarketOrder(
            Users user,
            Trades trade,
            String asset
    ) {
        if (user == null) {
            log.info("User data is null!");
            return;
        }

        if (null == trade) {
            log.info("No active short trade found for {}", asset);
            return;
        }

        try {
            BigDecimal closeQty = trade.getEntryExecutedQty();

            if (closeQty == null || closeQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Entry executed quantity is invalid for closing short trade.");
            }

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(
                    BinanceOrderRequest.builder()
                            .symbol(asset)
                            .side("BUY")
                            .amount(closeQty)
                            .apiKey(user.getApiKey())
                            .apiSecret(user.getApiSecret())
                            .build()
            );

            OrderSummary summary = summarizeOrderResponse(response);
            BigDecimal avgExitPrice = summary.getTotalCost().divide(summary.getTotalQty(), 8, RoundingMode.HALF_UP);

            trade.setExitOrderId(response.getOrderId());
            trade.setExitExecutedQty(summary.getTotalQty());
            trade.setExitExecutedQuoteQty(summary.getTotalCost());
            trade.setExitFee(summary.getTotalFee());
            trade.setExitFeeCurrency(summary.getFeeCurrency());
            trade.setExitPrice(avgExitPrice);
            trade.setExitTime(LocalDateTime.now());
            trade.setStatus("CLOSED");
            trade.setPlAmount(calculatePLAmount(trade.getEntryPrice(), avgExitPrice, trade.getEntryExecutedQty(), TradeType.SHORT));
            trade.setPlPercent(calculatePLPercentage(trade.getEntryPrice(), avgExitPrice, TradeType.SHORT));

            tradesRepository.save(trade);

            log.info("✅ Short position closed for {} at avg price: {}", asset, avgExitPrice);

        } catch (Exception e) {
            log.error("❌ Error closing short market order for {}", asset, e);
        }
    }




    public OrderSummary summarizeOrderResponse(BinanceOrderResponse response) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        String feeCurrency = null;

        if (response.getFills() != null && !response.getFills().isEmpty()) {
            for (BinanceOrderFill fill : response.getFills()) {
                BigDecimal qty = new BigDecimal(fill.getQty());
                BigDecimal price = new BigDecimal(fill.getPrice());
                BigDecimal commission = new BigDecimal(fill.getCommission());

                totalQty = totalQty.add(qty);
                totalCost = totalCost.add(price.multiply(qty));
                totalFee = totalFee.add(commission);

                if (feeCurrency == null) {
                    feeCurrency = fill.getCommissionAsset();
                }
            }
        }

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Close order executed but total filled quantity is zero.");
        }

        return new OrderSummary(totalQty, totalCost, totalFee, feeCurrency);
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

}
