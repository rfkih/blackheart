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

        if (state.getPeakEquity() == null || currentEquity.compareTo(state.getPeakEquity()) > 0) {
            state.setPeakEquity(currentEquity);
        }

        if (state.getPeakEquity() != null && state.getPeakEquity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal drawdownPercent = state.getPeakEquity()
                    .subtract(currentEquity)
                    .divide(state.getPeakEquity(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            if (state.getMaxDrawdownPercent() == null
                    || drawdownPercent.compareTo(state.getMaxDrawdownPercent()) > 0) {
                state.setMaxDrawdownPercent(drawdownPercent);
            }
        }
    }

    private BigDecimal calculateCurrentEquity(BacktestState state, BigDecimal currentClosePrice) {
        BacktestTrade activeTrade = state.getActiveTrade();

        if (activeTrade == null) {
            return safe(state.getCashBalance());
        }

        BigDecimal entryQuoteQty = safe(activeTrade.getTotalEntryQuoteQty());
        BigDecimal entryQty = safe(activeTrade.getTotalEntryQty());
        BigDecimal remainingQty = safe(activeTrade.getTotalRemainingQty());
        BigDecimal totalFeeAmount = safe(activeTrade.getTotalFeeAmount());
        BigDecimal entryPrice = safe(activeTrade.getAvgEntryPrice());

        if ("LONG".equalsIgnoreCase(activeTrade.getSide())) {
            BigDecimal markToMarketValue = remainingQty.multiply(currentClosePrice);
            BigDecimal unrealizedPnl = markToMarketValue
                    .subtract(remainingQty.multiply(entryPrice));

            return safe(state.getCashBalance())
                    .add(markToMarketValue)
                    .subtract(totalFeeAmount)
                    .add(safe(activeTrade.getRealizedPnlAmount()));
        }

        if ("SHORT".equalsIgnoreCase(activeTrade.getSide())) {
            BigDecimal unrealizedPnl = entryPrice
                    .subtract(currentClosePrice)
                    .multiply(remainingQty);

            return safe(state.getCashBalance())
                    .add(entryQuoteQty)
                    .add(unrealizedPnl)
                    .subtract(totalFeeAmount)
                    .add(safe(activeTrade.getRealizedPnlAmount()));
        }

        return safe(state.getCashBalance());
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}