package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

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

        // Multi-trade aware: sum mark-to-market across EVERY strategy's
        // active positions, not just the legacy mirror's. With multiple
        // strategies open under the cap, the legacy mirror would reflect
        // only the most-recently-opened trade and silently drop the rest
        // of the book from equity / drawdown calculations.
        java.util.Map<String, List<BacktestTradePosition>> byStrategy =
                state.getActiveTradePositionsByStrategy();
        if (!CollectionUtils.isEmpty(byStrategy)) {
            for (List<BacktestTradePosition> perStrategy : byStrategy.values()) {
                if (perStrategy == null) continue;
                equity = equity.add(positionsMarkToMarket(perStrategy, latestPrice));
            }
            return equity;
        }

        // Legacy single-strategy fallback (pre-B1 code path / single
        // strategy runs with no multi-trade entries).
        BacktestTrade activeTrade = state.getActiveTrade();
        List<BacktestTradePosition> activePositions = state.getActiveTradePositions();
        if (activeTrade == null || CollectionUtils.isEmpty(activePositions)) {
            return equity;
        }
        return equity.add(positionsMarkToMarket(activePositions, latestPrice));
    }

    private BigDecimal positionsMarkToMarket(
            List<BacktestTradePosition> positions, BigDecimal latestPrice
    ) {
        BigDecimal sum = ZERO;
        for (BacktestTradePosition position : positions) {
            sum = sum.add(positionContribution(position, latestPrice));
        }
        return sum;
    }

    private BigDecimal positionContribution(BacktestTradePosition position, BigDecimal latestPrice) {
        if (position == null || !"OPEN".equalsIgnoreCase(position.getStatus())) {
            return ZERO;
        }
        BigDecimal remainingQty = safe(position.getRemainingQty());
        BigDecimal entryPrice = safe(position.getEntryPrice());
        if (remainingQty.compareTo(ZERO) <= 0 || entryPrice.compareTo(ZERO) <= 0) {
            return ZERO;
        }
        if ("LONG".equalsIgnoreCase(position.getSide())) {
            return remainingQty.multiply(latestPrice);
        }
        // Synthetic short model: equity contribution = reserved quote notional + unrealized pnl.
        // Clamped at zero — max loss is the collateral put up at entry; markToMarketValue
        // cannot go negative (position liquidated at that point).
        BigDecimal reservedNotional = remainingQty.multiply(entryPrice);
        BigDecimal unrealizedPnl = entryPrice.subtract(latestPrice).multiply(remainingQty);
        return reservedNotional.add(unrealizedPnl).max(ZERO);
    }

    /**
     * Checks worst-case intra-bar drawdown using the given price (e.g. candle LOW for LONG,
     * candle HIGH for SHORT) without updating currentEquity or peakEquity.
     */
    public void checkIntraBarDrawdown(BacktestState state, BigDecimal worstCasePrice) {
        if (state == null || worstCasePrice == null || worstCasePrice.compareTo(ZERO) <= 0) {
            return;
        }

        if (state.getPeakEquity() == null || state.getPeakEquity().compareTo(ZERO) <= 0) {
            return;
        }

        BigDecimal worstEquity = calculateCurrentEquity(state, worstCasePrice);

        BigDecimal drawdownPct = state.getPeakEquity()
                .subtract(worstEquity)
                .divide(state.getPeakEquity(), 8, RoundingMode.HALF_UP)
                .multiply(HUNDRED);

        if (drawdownPct.compareTo(ZERO) > 0
                && (state.getMaxDrawdownPercent() == null
                || drawdownPct.compareTo(state.getMaxDrawdownPercent()) > 0)) {
            state.setMaxDrawdownPercent(drawdownPct);
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}