package id.co.blackheart.service.tradelistener;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
public class TradeListenerService {

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String EXIT_STOP_LOSS = "STOP_LOSS";
    private static final String EXIT_TAKE_PROFIT = "TAKE_PROFIT";

    public ListenerDecision evaluate(ListenerContext context) {
        if (context == null || context.getPositionSnapshot() == null || context.getLatestPrice() == null) {
            return ListenerDecision.none();
        }

        PositionSnapshot position = context.getPositionSnapshot();

        if (!position.isHasOpenPosition()) {
            return ListenerDecision.none();
        }

        if (SIDE_LONG.equalsIgnoreCase(position.getSide())) {
            return evaluateLong(position, context.getLatestPrice());
        }

        if (SIDE_SHORT.equalsIgnoreCase(position.getSide())) {
            return evaluateShort(position, context.getLatestPrice());
        }

        return ListenerDecision.none();
    }

    private ListenerDecision evaluateLong(PositionSnapshot position, BigDecimal latestPrice) {
        BigDecimal stopLoss = position.getCurrentStopLossPrice();
        BigDecimal takeProfit = position.getTakeProfitPrice();

        boolean stopHit = stopLoss != null
                && latestPrice.compareTo(stopLoss) <= 0;

        boolean takeProfitHit = takeProfit != null
                && latestPrice.compareTo(takeProfit) >= 0;


        if (stopHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_STOP_LOSS)
                    .exitPrice(stopLoss)
                    .exitTime(LocalDateTime.now())
                    .build();
        }

        if (takeProfitHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_TAKE_PROFIT)
                    .exitPrice(takeProfit)
                    .exitTime(LocalDateTime.now())
                    .build();
        }

        return ListenerDecision.none();
    }

    private ListenerDecision evaluateShort(PositionSnapshot position, BigDecimal latestPrice) {
        BigDecimal stopLoss = position.getCurrentStopLossPrice();
        BigDecimal takeProfit = position.getTakeProfitPrice();

        boolean stopHit = stopLoss != null
                && latestPrice.compareTo(stopLoss) >= 0;

        boolean takeProfitHit = takeProfit != null
                && latestPrice.compareTo(takeProfit) <= 0;


        if (stopHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_STOP_LOSS)
                    .exitPrice(stopLoss)
                    .exitTime(LocalDateTime.now())
                    .build();
        }

        if (takeProfitHit) {
            return ListenerDecision.builder()
                    .triggered(true)
                    .exitReason(EXIT_TAKE_PROFIT)
                    .exitPrice(takeProfit)
                    .exitTime(LocalDateTime.now())
                    .build();
        }

        return ListenerDecision.none();
    }
}