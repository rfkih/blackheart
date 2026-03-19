package id.co.blackheart.service.tradelistener;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.MarketData;
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

    public ListenerDecision evaluate(ListenerContext context) {
        if (context == null || context.getPositionSnapshot() == null || context.getMonitorCandle() == null) {
            return ListenerDecision.none();
        }

        PositionSnapshot position = context.getPositionSnapshot();
        MarketData candle = context.getMonitorCandle();

        if (!position.isHasOpenPosition()) {
            return ListenerDecision.none();
        }

        if (SIDE_LONG.equalsIgnoreCase(position.getSide())) {
            return evaluateLong(position, candle);
        }

        if (SIDE_SHORT.equalsIgnoreCase(position.getSide())) {
            return evaluateShort(position, candle);
        }

        return ListenerDecision.none();
    }

    private ListenerDecision evaluateLong(PositionSnapshot position, MarketData candle) {
        BigDecimal stopLoss = position.getCurrentStopLossPrice();
        BigDecimal takeProfit = position.getTakeProfitPrice();

        boolean stopHit = stopLoss != null
                && candle.getLowPrice() != null
                && candle.getLowPrice().compareTo(stopLoss) <= 0;

        boolean takeProfitHit = takeProfit != null
                && candle.getHighPrice() != null
                && candle.getHighPrice().compareTo(takeProfit) >= 0;

        if (stopHit && takeProfitHit) {
            // conservative rule: assume stop hit first
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_STOP_LOSS)
                    .exitPrice(stopLoss)
                    .exitTime(candle.getEndTime())
                    .build();
        }

        if (stopHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_STOP_LOSS)
                    .exitPrice(stopLoss)
                    .exitTime(candle.getEndTime())
                    .build();
        }

        if (takeProfitHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_TAKE_PROFIT)
                    .exitPrice(takeProfit)
                    .exitTime(candle.getEndTime())
                    .build();
        }

        return ListenerDecision.none();
    }

    private ListenerDecision evaluateShort(PositionSnapshot position, MarketData candle) {
        BigDecimal stopLoss = position.getCurrentStopLossPrice();
        BigDecimal takeProfit = position.getTakeProfitPrice();

        boolean stopHit = stopLoss != null
                && candle.getHighPrice() != null
                && candle.getHighPrice().compareTo(stopLoss) >= 0;

        boolean takeProfitHit = takeProfit != null
                && candle.getLowPrice() != null
                && candle.getLowPrice().compareTo(takeProfit) <= 0;

        if (stopHit && takeProfitHit) {
            // conservative rule: assume stop hit first
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_STOP_LOSS)
                    .exitPrice(stopLoss)
                    .exitTime(candle.getEndTime())
                    .build();
        }

        if (stopHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_STOP_LOSS)
                    .exitPrice(stopLoss)
                    .exitTime(candle.getEndTime())
                    .build();
        }

        if (takeProfitHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_TAKE_PROFIT)
                    .exitPrice(takeProfit)
                    .exitTime(candle.getEndTime())
                    .build();
        }

        return ListenerDecision.none();
    }
}