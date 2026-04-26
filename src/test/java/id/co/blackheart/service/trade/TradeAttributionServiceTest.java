package id.co.blackheart.service.trade;

import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.Trades;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the central identity:
 *   realized_pnl ≈ signal_alpha + execution_drift + sizing_residual
 *
 * If this drifts the attribution panel will quietly lie. Tests cover both
 * directions and a few edge cases (no slippage, no sizing change, missing
 * intent).
 */
class TradeAttributionServiceTest {

    private static final BigDecimal TOL = new BigDecimal("0.01");
    private final TradeAttributionService service = new TradeAttributionService();

    @Test
    void identityHoldsForLongTrade() {
        // Strategy fired at 100k, intended to risk 1000 USDT (= 0.01 BTC at 100k).
        // Got filled at 100100 (10 USDT slippage), traded 0.008 BTC actual
        // (vol-targeting shrank size), exited at 102000.
        Trades t = longTrade(
                /* intendedEntry */ "100000",
                /* intendedSizeUsdt */ "1000",  // = 0.01 BTC
                /* actualEntry */ "100100",
                /* actualQty */ "0.008",
                /* actualExit */ "102000",
                /* realized */ "15.20"); // (102000 - 100100) × 0.008 = 15.20

        TradeAttributionService.Attribution a = service.attribute(t).orElseThrow();
        BigDecimal sum = a.signalAlpha().add(a.executionDrift()).add(a.sizingResidual());
        assertTrue(sum.subtract(a.realizedPnl()).abs().compareTo(TOL) <= 0,
                "Identity broken: sum=" + sum + " realized=" + a.realizedPnl());

        // Sanity: for this scenario the strategy *would* have made
        // (102000-100000)×0.01 = 20 USDT at intended size + entry → positive alpha.
        assertTrue(a.signalAlpha().compareTo(BigDecimal.ZERO) > 0);
        // Entry slippage cost: (100000-100100)×0.008 = -0.8 (worse fill on LONG).
        assertTrue(a.executionDrift().compareTo(BigDecimal.ZERO) < 0);
        // Sizing residual: actual was smaller than intended on a winning trade
        // → negative leg (we left alpha on the table).
        assertTrue(a.sizingResidual().compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void identityHoldsForShortTrade() {
        // SHORT: intended 0.01 BTC at 100k, filled at 99900 (worse fill — entered low),
        // traded 0.012 actual (vol-targeting grew size), covered at 98000.
        Trades t = shortTrade(
                /* intendedEntry */ "100000",
                /* intendedSizeBtc */ "0.01",
                /* actualEntry */ "99900",
                /* actualQty */ "0.012",
                /* actualExit */ "98000",
                /* realized */ "22.80"); // (99900 - 98000) × 0.012 = 22.80

        TradeAttributionService.Attribution a = service.attribute(t).orElseThrow();
        BigDecimal sum = a.signalAlpha().add(a.executionDrift()).add(a.sizingResidual());
        assertTrue(sum.subtract(a.realizedPnl()).abs().compareTo(TOL) <= 0,
                "Identity broken (SHORT): sum=" + sum + " realized=" + a.realizedPnl());
    }

    @Test
    void cleanFillWithExactSizeYieldsZeroDriftAndResidual() {
        // No slippage, intended size = actual size — all P&L is signal alpha.
        Trades t = longTrade("100000", "1000", "100000", "0.01", "102000", "20.00");
        TradeAttributionService.Attribution a = service.attribute(t).orElseThrow();

        assertEquals(0, a.executionDrift().compareTo(BigDecimal.ZERO));
        assertEquals(0, a.sizingResidual().compareTo(BigDecimal.ZERO));
        assertTrue(a.signalAlpha().subtract(new BigDecimal("20")).abs()
                .compareTo(TOL) <= 0);
    }

    @Test
    void emptyWhenTradeStillOpen() {
        Trades t = longTrade("100000", "1000", "100100", "0.008", null, null);
        assertEquals(Optional.empty(), service.attribute(t));
    }

    @Test
    void emptyWhenIntentMissing() {
        // Legacy row — pre-Phase 2c, no intended_* recorded. Better to render
        // "—" than fabricate.
        Trades t = new Trades();
        t.setSide("LONG");
        t.setAvgEntryPrice(new BigDecimal("100100"));
        t.setAvgExitPrice(new BigDecimal("102000"));
        t.setTotalEntryQty(new BigDecimal("0.008"));
        t.setRealizedPnlAmount(new BigDecimal("15.20"));
        // intentedEntryPrice / intendedSize deliberately null.
        assertEquals(Optional.empty(), service.attribute(t));
    }

    private static Trades longTrade(String intendedEntry, String intendedSizeUsdt,
                                    String actualEntry, String actualQty,
                                    String actualExit, String realized) {
        Trades t = new Trades();
        t.setSide("LONG");
        t.setIntendedEntryPrice(new BigDecimal(intendedEntry));
        t.setIntendedSize(new BigDecimal(intendedSizeUsdt));
        t.setAvgEntryPrice(new BigDecimal(actualEntry));
        t.setTotalEntryQty(new BigDecimal(actualQty));
        t.setAvgExitPrice(actualExit == null ? null : new BigDecimal(actualExit));
        t.setRealizedPnlAmount(realized == null ? null : new BigDecimal(realized));
        return t;
    }

    private static Trades shortTrade(String intendedEntry, String intendedSizeBtc,
                                     String actualEntry, String actualQty,
                                     String actualExit, String realized) {
        Trades t = new Trades();
        t.setSide("SHORT");
        t.setIntendedEntryPrice(new BigDecimal(intendedEntry));
        t.setIntendedSize(new BigDecimal(intendedSizeBtc));
        t.setAvgEntryPrice(new BigDecimal(actualEntry));
        t.setTotalEntryQty(new BigDecimal(actualQty));
        t.setAvgExitPrice(actualExit == null ? null : new BigDecimal(actualExit));
        t.setRealizedPnlAmount(realized == null ? null : new BigDecimal(realized));
        return t;
    }

    // ── BacktestTrade overload (Phase 2c-followup) ──────────────────────────

    /**
     * Identity holds for backtest trades the same way it holds for live.
     * In backtest mode the only non-zero "drift" is simulated slippage —
     * sizing residual is always 0 since vol-targeting doesn't run.
     */
    @Test
    void identityHoldsForBacktestLong() {
        BacktestTrade t = new BacktestTrade();
        t.setSide("LONG");
        t.setIntendedEntryPrice(new BigDecimal("100000"));
        t.setIntendedSize(new BigDecimal("1000"));        // 1000 USDT notional
        t.setAvgEntryPrice(new BigDecimal("100050"));     // 5 bps simulated slippage
        t.setTotalEntryQty(new BigDecimal("0.01"));       // = 1000/100000 (no scaling)
        t.setAvgExitPrice(new BigDecimal("102000"));
        t.setRealizedPnlAmount(new BigDecimal("19.50"));  // (102000 - 100050) × 0.01

        TradeAttributionService.Attribution a = service.attribute(t).orElseThrow();
        BigDecimal sum = a.signalAlpha().add(a.executionDrift()).add(a.sizingResidual());
        assertTrue(sum.subtract(a.realizedPnl()).abs().compareTo(TOL) <= 0,
                "backtest identity broken: sum=" + sum + " realized=" + a.realizedPnl());

        // No vol-targeting in backtest → sizing residual ≈ 0.
        assertEquals(0, a.sizingResidual().compareTo(BigDecimal.ZERO),
                "backtest must have zero sizing residual (no vol-targeting on backtest path)");
    }

    @Test
    void backtestEmptyWhenLegacyRow() {
        BacktestTrade t = new BacktestTrade();
        t.setSide("LONG");
        // intended* deliberately null — pre-Phase 2c row.
        t.setAvgEntryPrice(new BigDecimal("100050"));
        t.setAvgExitPrice(new BigDecimal("102000"));
        t.setTotalEntryQty(new BigDecimal("0.01"));
        t.setRealizedPnlAmount(new BigDecimal("19.50"));
        assertEquals(Optional.empty(), service.attribute(t));
    }
}
