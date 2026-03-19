package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestTrade;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BacktestStateService {

    public void updateEquityAndDrawdown(BacktestState state, BigDecimal currentClosePrice) {
        BigDecimal currentEquity = calculateCurrentEquity(state, currentClosePrice);

        if (currentEquity.compareTo(state.getPeakEquity()) > 0) {
            state.setPeakEquity(currentEquity);
        }

        if (state.getPeakEquity() != null && state.getPeakEquity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal drawdownPercent = state.getPeakEquity()
                    .subtract(currentEquity)
                    .divide(state.getPeakEquity(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            if (drawdownPercent.compareTo(state.getMaxDrawdownPercent()) > 0) {
                state.setMaxDrawdownPercent(drawdownPercent);
            }
        }
    }

    private BigDecimal calculateCurrentEquity(BacktestState state, BigDecimal currentClosePrice) {
        BacktestTrade activeTrade = state.getActiveTrade();

        if (activeTrade == null) {
            return safe(state.getCashBalance());
        }

        BigDecimal entryQuoteQty = safe(activeTrade.getEntryQuoteQty());
        BigDecimal entryQty = safe(activeTrade.getEntryQty());
        BigDecimal entryFee = safe(activeTrade.getEntryFee());
        BigDecimal entryPrice = safe(activeTrade.getEntryPrice());

        if ("LONG".equalsIgnoreCase(activeTrade.getSide())) {
            BigDecimal markToMarketValue = entryQty.multiply(currentClosePrice);
            BigDecimal unrealizedPnl = markToMarketValue
                    .subtract(entryQuoteQty)
                    .subtract(entryFee);

            return entryQuoteQty.add(unrealizedPnl);
        }

        if ("SHORT".equalsIgnoreCase(activeTrade.getSide())) {
            BigDecimal unrealizedPnl = entryPrice
                    .subtract(currentClosePrice)
                    .multiply(entryQty)
                    .subtract(entryFee);

            return entryQuoteQty.add(unrealizedPnl);
        }

        return safe(state.getCashBalance());
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}