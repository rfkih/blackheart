package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class BacktestStateService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public void updateEquityAndDrawdown(BacktestState state, BigDecimal latestPrice) {
        if (state == null || latestPrice == null || latestPrice.compareTo(ZERO) <= 0) {
            return;
        }

        BigDecimal currentEquity = calculateCurrentEquity(state, latestPrice);
        state.setCurrentEquity(currentEquity);

        if (state.getPeakEquity() == null || currentEquity.compareTo(state.getPeakEquity()) > 0) {
            state.setPeakEquity(currentEquity);
        }

        if (state.getPeakEquity() != null && state.getPeakEquity().compareTo(ZERO) > 0) {
            BigDecimal drawdownPct = state.getPeakEquity()
                    .subtract(currentEquity)
                    .divide(state.getPeakEquity(), 8, RoundingMode.HALF_UP)
                    .multiply(HUNDRED);

            if (state.getMaxDrawdownPercent() == null
                    || drawdownPct.compareTo(state.getMaxDrawdownPercent()) > 0) {
                state.setMaxDrawdownPercent(drawdownPct);
            }
        }
    }

    public BigDecimal calculateCurrentEquity(BacktestState state, BigDecimal latestPrice) {
        BigDecimal equity = safe(state.getCashBalance());

        BacktestTrade activeTrade = state.getActiveTrade();
        List<BacktestTradePosition> activePositions = state.getActiveTradePositions();

        if (activeTrade == null || activePositions == null || activePositions.isEmpty()) {
            return equity;
        }

        for (BacktestTradePosition position : activePositions) {
            if (position == null || !"OPEN".equalsIgnoreCase(position.getStatus())) {
                continue;
            }

            BigDecimal remainingQty = safe(position.getRemainingQty());
            BigDecimal entryPrice = safe(position.getEntryPrice());

            if (remainingQty.compareTo(ZERO) <= 0 || entryPrice.compareTo(ZERO) <= 0) {
                continue;
            }

            BigDecimal markToMarketValue;
            BigDecimal unrealizedPnl;

            if ("LONG".equalsIgnoreCase(position.getSide())) {
                markToMarketValue = remainingQty.multiply(latestPrice);
                unrealizedPnl = latestPrice.subtract(entryPrice).multiply(remainingQty);
            } else {
                /**
                 * Synthetic short model:
                 * equity contribution = reserved quote notional + unrealized pnl
                 * Clamped at zero — max loss is the collateral put up at entry;
                 * markToMarketValue cannot go negative (position liquidated at that point).
                 */
                BigDecimal reservedNotional = remainingQty.multiply(entryPrice);
                unrealizedPnl = entryPrice.subtract(latestPrice).multiply(remainingQty);
                markToMarketValue = reservedNotional.add(unrealizedPnl).max(ZERO);
            }

            equity = equity.add(markToMarketValue);
        }

        return equity;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}