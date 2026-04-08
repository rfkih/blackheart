package id.co.blackheart.service.tradelistener;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class TradeListenerService {

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String EXIT_STOP_LOSS = "STOP_LOSS";
    private static final String EXIT_TAKE_PROFIT = "TAKE_PROFIT";
    private static final String EXIT_TRAILING_STOP = "TRAILING_STOP";

    public ListenerDecision evaluate(ListenerContext context) {
        if (context == null || context.getPositionSnapshot() == null || context.getLatestPrice() == null) {
            return ListenerDecision.none();
        }

        PositionSnapshot position = context.getPositionSnapshot();

        if (!position.isHasOpenPosition()) {
            return ListenerDecision.none();
        }

        if (SIDE_LONG.equalsIgnoreCase(position.getSide())) {
            return evaluateLong(position, context.getLatestPrice(), context.getCandleOpen(), context.getCandleHigh(), context.getCandleLow());
        }

        if (SIDE_SHORT.equalsIgnoreCase(position.getSide())) {
            return evaluateShort(position, context.getLatestPrice(), context.getCandleOpen(), context.getCandleHigh(), context.getCandleLow());
        }

        return ListenerDecision.none();
    }

    private ListenerDecision evaluateLong(PositionSnapshot position, BigDecimal latestPrice, BigDecimal candleOpen, BigDecimal candleHigh, BigDecimal candleLow) {
        BigDecimal stopLoss = position.getCurrentStopLossPrice();
        BigDecimal trailingStop = position.getTrailingStopPrice();
        BigDecimal takeProfit = position.getTakeProfitPrice();

        // For LONG: stops are hit when price goes LOW enough; TP is hit when price goes HIGH enough
        BigDecimal stopCheckPrice = candleLow != null ? candleLow : latestPrice;
        BigDecimal tpCheckPrice = candleHigh != null ? candleHigh : latestPrice;

        boolean stopHit = stopLoss != null && stopCheckPrice.compareTo(stopLoss) <= 0;
        boolean trailingHit = trailingStop != null && stopCheckPrice.compareTo(trailingStop) <= 0;
        boolean takeProfitHit = takeProfit != null && tpCheckPrice.compareTo(takeProfit) >= 0;

        // Trailing stop takes priority over fixed SL (trailing is always >= SL after BE applied)
        boolean adverseHit = trailingHit || stopHit;

        // Same-bar conflict: both adverse stop and TP were touched on the same candle.
        // Use bar direction to infer which was hit first.
        // Bullish bar (close > open) → price moved up first → TP hit first for LONG.
        // Bearish bar or unknown direction → SL/trailing hit first (conservative).
        if (adverseHit && takeProfitHit && candleOpen != null) {
            boolean bullishBar = latestPrice.compareTo(candleOpen) > 0;
            if (bullishBar) {
                return ListenerDecision.builder()
                        .triggered(true)
                        .exitReason(EXIT_TAKE_PROFIT)
                        .exitPrice(takeProfit)
                        .build();
            }
        }

        if (trailingHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_TRAILING_STOP)
                    .exitPrice(trailingStop)
                    .build();
        }

        if (stopHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_STOP_LOSS)
                    .exitPrice(stopLoss)
                    .build();
        }

        if (takeProfitHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_TAKE_PROFIT)
                    .exitPrice(takeProfit)
                    .build();
        }

        return ListenerDecision.none();
    }

    private ListenerDecision evaluateShort(PositionSnapshot position, BigDecimal latestPrice, BigDecimal candleOpen, BigDecimal candleHigh, BigDecimal candleLow) {
        BigDecimal stopLoss = position.getCurrentStopLossPrice();
        BigDecimal trailingStop = position.getTrailingStopPrice();
        BigDecimal takeProfit = position.getTakeProfitPrice();

        // For SHORT: stops are hit when price goes HIGH enough; TP is hit when price goes LOW enough
        BigDecimal stopCheckPrice = candleHigh != null ? candleHigh : latestPrice;
        BigDecimal tpCheckPrice = candleLow != null ? candleLow : latestPrice;

        boolean stopHit = stopLoss != null && stopCheckPrice.compareTo(stopLoss) >= 0;
        boolean trailingHit = trailingStop != null && stopCheckPrice.compareTo(trailingStop) >= 0;
        boolean takeProfitHit = takeProfit != null && tpCheckPrice.compareTo(takeProfit) <= 0;

        boolean adverseHit = trailingHit || stopHit;

        // Same-bar conflict: bearish bar (close < open) → price moved down first → TP hit first for SHORT.
        // Bullish bar or unknown direction → SL/trailing hit first (conservative).
        if (adverseHit && takeProfitHit && candleOpen != null) {
            boolean bearishBar = latestPrice.compareTo(candleOpen) < 0;
            if (bearishBar) {
                return ListenerDecision.builder()
                        .triggered(true)
                        .exitReason(EXIT_TAKE_PROFIT)
                        .exitPrice(takeProfit)
                        .build();
            }
        }

        if (trailingHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_TRAILING_STOP)
                    .exitPrice(trailingStop)
                    .build();
        }

        if (stopHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_STOP_LOSS)
                    .exitPrice(stopLoss)
                    .build();
        }

        if (takeProfitHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_TAKE_PROFIT)
                    .exitPrice(takeProfit)
                    .build();
        }

        return ListenerDecision.none();
    }
}