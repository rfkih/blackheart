package id.co.blackheart.service.trade;

import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.Trades;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Decomposes a closed trade's realized P&L into three orthogonal legs:
 *
 * <ul>
 *   <li><b>Signal alpha</b> — the P&L the strategy <i>would have produced</i>
 *   if it had filled at the intended entry price with the intended size,
 *   exiting at the actual exit price. Captures "did the price move in our
 *   favor between when we fired and when we got out?"</li>
 *
 *   <li><b>Execution drift</b> — entry slippage. The cost of getting filled
 *   at a worse price than the strategy decided on, multiplied by the
 *   actual size traded. Positive value = we gave up alpha at the open.</li>
 *
 *   <li><b>Sizing residual</b> — the contribution attributable to the gap
 *   between intended size and actual size (where vol-targeting and other
 *   adjustments live). On a strategy whose vol-targeting <i>shrank</i> a
 *   profitable trade, this leg shows up negative — exactly the diagnostic
 *   we need to know whether vol-targeting is helping or hurting.</li>
 * </ul>
 *
 * <p>Identity: {@code realized = signal_alpha + exec_drift + sizing_resid}.
 * Verified algebraically — see {@code TradeAttributionServiceTest}.
 *
 * <p>Returns {@link Optional#empty()} when the trade lacks the intent
 * fields (legacy rows) or hasn't closed yet — better to render "—" than
 * fabricate a number.
 */
@Service
public class TradeAttributionService {

    public record Attribution(
            BigDecimal realizedPnl,
            BigDecimal signalAlpha,
            BigDecimal executionDrift,
            BigDecimal sizingResidual,
            BigDecimal entrySlippagePct,
            BigDecimal sizeRatio
    ) {}

    public Optional<Attribution> attribute(Trades trade) {
        if (trade == null) return Optional.empty();
        return computeAttribution(
                trade.getSide(),
                trade.getIntendedEntryPrice(),
                trade.getIntendedSize(),
                trade.getAvgEntryPrice(),
                trade.getAvgExitPrice(),
                trade.getTotalEntryQty(),
                trade.getRealizedPnlAmount());
    }

    /**
     * Same decomposition for backtest trades. Phase 2c stamped intent on
     * {@code backtest_trade} so the math is identical — just a different
     * source row. In backtest mode {@code intended_size} equals the
     * decision-time size (no vol-targeting on the backtest path), so
     * sizing residual is always 0 and the only non-zero "drift" is the
     * simulated slippage. Useful for confirming the slippage model is
     * sane: alpha + drift should sum to realized.
     */
    public Optional<Attribution> attribute(BacktestTrade trade) {
        if (trade == null) return Optional.empty();
        return computeAttribution(
                trade.getSide(),
                trade.getIntendedEntryPrice(),
                trade.getIntendedSize(),
                trade.getAvgEntryPrice(),
                trade.getAvgExitPrice(),
                trade.getTotalEntryQty(),
                trade.getRealizedPnlAmount());
    }

    /**
     * Pure-math core. Both {@link Trades} and {@link BacktestTrade}
     * deliver the same five primitives — side, intended entry/size,
     * actual entry/exit/qty — so the decomposition is shared.
     */
    private Optional<Attribution> computeAttribution(
            String side,
            BigDecimal intendedEntry,
            BigDecimal intendedSizeRaw,
            BigDecimal actualEntry,
            BigDecimal actualExit,
            BigDecimal actualSize,
            BigDecimal realizedPnl) {
        if (actualExit == null || actualEntry == null || actualSize == null) return Optional.empty();
        if (intendedEntry == null || intendedSizeRaw == null) return Optional.empty();

        // Both sizes need to be in BASE currency (BTC) for the price-difference
        // math to multiply out to USDT realized P&L. The strategy stores
        // intent in the side's natural unit:
        //   LONG  → notional USDT  → divide by intended price to get BTC qty
        //   SHORT → BTC qty        → already in base currency
        boolean isShort = "SHORT".equalsIgnoreCase(side);
        BigDecimal intendedSize;
        if (isShort) {
            intendedSize = intendedSizeRaw;
        } else {
            if (intendedEntry.signum() <= 0) return Optional.empty();
            intendedSize = intendedSizeRaw.divide(intendedEntry, 12, RoundingMode.HALF_UP);
        }

        // Side sign: LONG profits when exit > entry, SHORT when exit < entry.
        // The decomposition algebra is symmetric — apply a sign flip for
        // SHORT so all three legs add to realized P&L correctly.
        int sign = isShort ? -1 : 1;
        BigDecimal s = BigDecimal.valueOf(sign);

        BigDecimal signalAlpha = actualExit.subtract(intendedEntry)
                .multiply(intendedSize).multiply(s);
        BigDecimal execDrift = intendedEntry.subtract(actualEntry)
                .multiply(actualSize).multiply(s);
        BigDecimal sizingResid = actualSize.subtract(intendedSize)
                .multiply(actualExit.subtract(intendedEntry)).multiply(s);

        BigDecimal entrySlipPct = null;
        if (intendedEntry.signum() > 0) {
            entrySlipPct = actualEntry.subtract(intendedEntry)
                    .divide(intendedEntry, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .multiply(s);
        }
        BigDecimal sizeRatio = null;
        if (intendedSize.signum() > 0) {
            sizeRatio = actualSize.divide(intendedSize, 6, RoundingMode.HALF_UP);
        }

        return Optional.of(new Attribution(
                realizedPnl,
                signalAlpha.setScale(8, RoundingMode.HALF_UP),
                execDrift.setScale(8, RoundingMode.HALF_UP),
                sizingResid.setScale(8, RoundingMode.HALF_UP),
                entrySlipPct,
                sizeRatio
        ));
    }
}
