package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.model.AccountStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class StrategyHelper {

    /** Latch — log the leverage-clamp warning once per JVM lifetime. We don't
     *  want this firing per tick across a 100-cell sweep with maxAlloc=5.0;
     *  one WARN at first occurrence is enough for the operator to notice. */
    private static final AtomicBoolean LEVERAGE_CLAMP_WARNED = new AtomicBoolean(false);

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";
    private static final String SOURCE_LIVE = "live";
    private static final String SOURCE_BACKTEST = "backtest";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * Trade-amount sizing. {@code AccountStrategy.capitalAllocationPct} is
     * the single sizing knob: at 50% allocation a 100 USDT account places a
     * 50 USDT trade. No further per-trade-risk multiplier is applied here —
     * the per-trade-risk concept lives in the strategy's stop-distance math
     * (R-multiple sizing) when relevant, not in the trade-amount derivation.
     *
     * <p>Returns ZERO when no allocation is set; the executor's "invalid
     * trade amount" check then skips the entry. capital_allocation_pct is
     * the canonical sizing field — a strategy without it doesn't trade.
     */
    public BigDecimal calculateEntryNotional(EnrichedStrategyContext context, String side) {
        if (ObjectUtils.isEmpty(context) || !StringUtils.hasText(side)) return ZERO;

        BigDecimal allocFraction = resolveCapitalAllocationFraction(context);
        if (allocFraction.signum() <= 0) return ZERO;

        if (SIDE_LONG.equalsIgnoreCase(side))  return longEntryNotional(context, allocFraction);
        if (SIDE_SHORT.equalsIgnoreCase(side)) return shortEntryNotional(context, allocFraction);
        return ZERO;
    }

    /** LONG legs always size off USDT — {@code cashBalance × allocFraction}. */
    private BigDecimal longEntryNotional(EnrichedStrategyContext context, BigDecimal allocFraction) {
        return cashBasedNotional(context.getCashBalance(), allocFraction);
    }

    /** SHORT in live mode sizes off the BTC inventory the user actually owns
     *  ({@code assetBalance × price × allocFraction}); backtest mode sizes off
     *  USDT cash like LONG, since the engine is cash-1× and treats SHORT as
     *  symmetrical for sizing purposes. */
    private BigDecimal shortEntryNotional(EnrichedStrategyContext context, BigDecimal allocFraction) {
        if (SOURCE_LIVE.equalsIgnoreCase(resolveExecutionSource(context))) {
            return liveShortNotional(context, allocFraction);
        }
        return cashBasedNotional(context.getCashBalance(), allocFraction);
    }

    private static BigDecimal cashBasedNotional(BigDecimal cashBalance, BigDecimal allocFraction) {
        if (cashBalance == null || cashBalance.compareTo(ZERO) <= 0) return ZERO;
        return cashBalance.multiply(allocFraction).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal liveShortNotional(EnrichedStrategyContext context, BigDecimal allocFraction) {
        BigDecimal assetBalance = context.getAssetBalance();
        BigDecimal price = context.getMarketData() != null
                ? context.getMarketData().getClosePrice()
                : null;
        if (assetBalance == null || assetBalance.compareTo(ZERO) <= 0
                || price == null || price.compareTo(ZERO) <= 0) {
            return ZERO;
        }
        return assetBalance.multiply(price).multiply(allocFraction)
                .setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * SHORT position size in BASE currency (BTC qty). Same single-knob
     * sizing as {@link #calculateEntryNotional}: {@code assetBalance ×
     * allocFraction}. Strategies that need per-trade-risk-aware sizing
     * (e.g. R-based qty from stop distance) compute that on top of this
     * base; this helper just returns the strategy's allocated slice.
     */
    public BigDecimal calculateShortPositionSize(EnrichedStrategyContext context) {
        BigDecimal allocFraction = resolveCapitalAllocationFraction(context);
        if (allocFraction.signum() <= 0) return ZERO;

        BigDecimal assetBalance = context.getAssetBalance();
        if (assetBalance == null || assetBalance.compareTo(ZERO) <= 0) return ZERO;

        return assetBalance.multiply(allocFraction).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Risk-based USDT notional. Sizes the position so the loss when price
     * reaches {@code stopLossPrice} equals approximately
     * {@code riskPct × cashBalance}, then caps at
     * {@code maxAllocationPct × cashBalance} so the trade still executes
     * when the ideal notional would exceed available balance.
     *
     * <p>Concretely:
     * <pre>
     *   stopDistancePct = |entry − stop| / entry
     *   ideal           = cashBalance × riskPct / stopDistancePct
     *   cap             = cashBalance × min(maxAllocationPct, 1.0)
     *   notional        = min(ideal, cap)
     * </pre>
     *
     * <p>Returns ZERO when:
     * <ul>
     *   <li>{@code riskPct} is null or non-positive — caller should fall back
     *       to {@link #calculateEntryNotional} (legacy notional sizing).</li>
     *   <li>Entry, stop, or stop-distance are non-positive (degenerate setup).</li>
     *   <li>{@code cashBalance} is non-positive (no capital to risk).</li>
     * </ul>
     *
     * <p>When {@code maxAllocationPct} is null/non-positive, the existing
     * {@code account_strategy.capital_allocation_pct} (capped at 100%) is
     * used as the cap fraction — preserves the balance-safe behaviour for
     * accounts that haven't opted into a separate cap value.
     *
     * <p><b>Hard cap at 1.0:</b> the backtest engine is cash-1× and the spot
     * executors check the actual balance, so a notional above {@code cashBalance}
     * would just fail the balance check downstream — no leverage is gained.
     * Inputs above 1.0 are silently clamped here. When futures-leverage
     * support lands in the backtest engine, lift this clamp.
     *
     * <p><b>SHORT-on-spot caveat:</b> the cap is computed from {@code cashBalance}
     * (USDT). For futures and backtest this is correct — USDT is the margin
     * currency. For LIVE SPOT SHORT, the executor sells from
     * {@code assetBalance} (BTC) and would reject a USDT-sized notional that
     * doesn't translate back to a coin balance the user actually owns.
     * Strategies that may run on spot live should leave {@code riskPct} null
     * for the SHORT path, or the caller should size against
     * {@code assetBalance × entryPrice} explicitly.
     */
    public BigDecimal calculateRiskBasedNotional(
            EnrichedStrategyContext context,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal riskPct,
            BigDecimal maxAllocationPct) {
        if (!hasValidRiskInputs(context, entryPrice, stopLossPrice, riskPct)) return ZERO;

        // LONG: stop must be strictly below entry. Strategies validate this
        // upstream (riskPerUnit > 0), but a wrong-sided stop slipping through
        // would compute a positive notional via |entry − stop| and open a
        // guaranteed-loss trade. Defense in depth — return ZERO so the
        // strategy holds.
        if (stopLossPrice.compareTo(entryPrice) >= 0) return ZERO;

        BigDecimal stopDistance = entryPrice.subtract(stopLossPrice).abs();
        if (stopDistance.signum() <= 0) return ZERO;
        BigDecimal stopDistancePct = stopDistance.divide(entryPrice, 8, RoundingMode.HALF_UP);
        if (stopDistancePct.signum() <= 0) return ZERO;

        BigDecimal capital = safe(context.getCashBalance());
        if (capital.signum() <= 0) return ZERO;

        BigDecimal idealNotional = capital.multiply(riskPct)
                .divide(stopDistancePct, 8, RoundingMode.HALF_UP);

        BigDecimal capFraction = resolveCapFraction(context, maxAllocationPct);
        if (capFraction.signum() <= 0) return ZERO;

        BigDecimal cap = capital.multiply(capFraction);
        return idealNotional.min(cap).setScale(8, RoundingMode.HALF_UP);
    }

    /** Risk-based sizing requires all four inputs to be present and positive.
     *  When any is missing, callers fall back to {@link #calculateEntryNotional}
     *  (legacy capital-allocation sizing). */
    private static boolean hasValidRiskInputs(
            EnrichedStrategyContext context,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal riskPct) {
        return context != null
                && riskPct != null && riskPct.signum() > 0
                && entryPrice != null && entryPrice.signum() > 0
                && stopLossPrice != null && stopLossPrice.signum() > 0;
    }

    /** Resolve the cap fraction from the explicit {@code maxAllocationPct} or
     *  fall back to the strategy's stored capital allocation. Clamps any value
     *  above 1.0 to 1.0 because the backtest engine is cash-1× and spot
     *  executors balance-check against {@code cashBalance} — values above 1.0
     *  yield no leverage, only downstream rejection. The clamp warning fires
     *  once per JVM lifetime so a misconfigured sweep gets visible attention
     *  without spamming logs. */
    private BigDecimal resolveCapFraction(EnrichedStrategyContext context, BigDecimal maxAllocationPct) {
        BigDecimal capFraction = (maxAllocationPct != null && maxAllocationPct.signum() > 0)
                ? maxAllocationPct
                : resolveCapitalAllocationFraction(context);
        if (capFraction.compareTo(ONE) > 0) {
            warnLeverageClampOnce(capFraction);
            return ONE;
        }
        return capFraction;
    }

    private static void warnLeverageClampOnce(BigDecimal capFraction) {
        if (LEVERAGE_CLAMP_WARNED.compareAndSet(false, true)) {
            log.warn("Risk-based sizing: maxAllocationPct={} clamped to 1.0 — "
                    + "backtest engine is cash-1× (no leverage). Sweeps over [1.0, N] "
                    + "will produce identical results above 1.0 until futures-leverage "
                    + "support lands. Suppressing further occurrences.", capFraction);
        }
    }

    /**
     * V55 — unified LONG-entry sizing.
     *
     * <p><b>Picker semantics</b>:
     * <ul>
     *   <li>Toggle <b>off</b> → legacy {@link #calculateEntryNotional}
     *       (allocation-as-direct-size).</li>
     *   <li>Toggle <b>on</b> but {@code riskPct} unset/non-positive → also
     *       falls back to legacy. The toggle alone isn't enough to commit to
     *       risk-based math; treat half-configured rows as a config gap, not
     *       a "hold every trade" signal.</li>
     *   <li>Toggle <b>on</b> AND {@code riskPct} valid → strict risk-based
     *       sizing. ZERO from {@link #calculateRiskBasedNotional} means the
     *       strategy must HOLD — degenerate stop, no cash, wrong-sided stop,
     *       missing inventory, etc. We do NOT fall back to legacy here:
     *       silently sizing a full-allocation position when the operator
     *       opted into controlled risk would defeat the toggle's intent.</li>
     * </ul>
     *
     * <p>Used by legacy strategies (LSR / VCB / VBO / FundingCarry). SHORT
     * counterpart is {@link #calculateShortEntryQty}. Spec-driven engines
     * (DCB / MMR / MRO / TPR) carry their own risk config in spec body params
     * and don't read this toggle — the frontend hides the input for them.
     */
    public BigDecimal calculateLongEntryNotional(EnrichedStrategyContext context,
                                                 BigDecimal entryPrice,
                                                 BigDecimal stopLossPrice) {
        AccountStrategy as = context == null ? null : context.getAccountStrategy();
        boolean toggleOn = as != null && Boolean.TRUE.equals(as.getUseRiskBasedSizing());
        boolean riskPctSet = as != null
                && as.getRiskPct() != null
                && as.getRiskPct().signum() > 0;
        if (!toggleOn || !riskPctSet) {
            return calculateEntryNotional(context, SIDE_LONG);
        }
        return calculateRiskBasedNotional(
                context, entryPrice, stopLossPrice, as.getRiskPct(), /*maxAllocationPct=*/null);
    }

    /**
     * V55 — unified SHORT-entry sizing (BTC qty). When the strategy has
     * {@code useRiskBasedSizing = TRUE} and a positive {@code riskPct}, sizes
     * via {@link #calculateRiskBasedShortQty} (BTC qty derived from a
     * cash-denominated risk budget × stop distance, capped by inventory).
     * Otherwise falls through to {@link #calculateShortPositionSize} (legacy
     * allocation-as-direct-fraction).
     *
     * <p>Symmetric with {@link #calculateLongEntryNotional}: same risk budget
     * basis ({@code cashBalance × riskPct}), same stop-distance derivation;
     * only the output unit and the cap basis differ — LONG returns USDT
     * notional capped by cash, SHORT returns BTC qty capped by asset balance
     * (which is the synthetic cash-equivalent BTC in backtest mode, so the
     * cap meaning is preserved across live + backtest).
     *
     * <p>Falls back to legacy sizing on ZERO output (degenerate stop, missing
     * inventory) — same defensive pattern as the LONG path.
     */
    public BigDecimal calculateShortEntryQty(EnrichedStrategyContext context,
                                             BigDecimal entryPrice,
                                             BigDecimal stopLossPrice) {
        AccountStrategy as = context == null ? null : context.getAccountStrategy();
        boolean toggleOn = as != null && Boolean.TRUE.equals(as.getUseRiskBasedSizing());
        boolean riskPctSet = as != null
                && as.getRiskPct() != null
                && as.getRiskPct().signum() > 0;
        // Same picker semantics as calculateLongEntryNotional — fall back to
        // legacy only when config is incomplete (toggle off or riskPct unset).
        // When the operator opted in fully, ZERO from the risk math means
        // HOLD; falling back to legacy on a cash=0 SHORT would over-trade
        // the full BTC inventory, defeating the controlled-risk intent.
        if (!toggleOn || !riskPctSet) {
            return calculateShortPositionSize(context);
        }
        return calculateRiskBasedShortQty(
                context, entryPrice, stopLossPrice, as.getRiskPct(), /*maxAllocationPct=*/null);
    }

    /**
     * V55 — risk-based SHORT sizing in BTC qty. Mirror of
     * {@link #calculateRiskBasedNotional} but projects to a coin quantity
     * because spot SHORT executors match {@code positionSize} (BTC) against
     * the asset balance.
     *
     * <pre>
     *   stopDistance = |entry − stop|                       (USDT per BTC)
     *   idealQtyBtc  = cashBalance × riskPct / stopDistance (BTC qty)
     *   capQtyBtc    = assetBalance × min(maxAlloc, 1.0)    (inventory cap)
     *   qty          = min(idealQtyBtc, capQtyBtc)
     * </pre>
     *
     * <p>The risk budget is denominated in cashBalance × riskPct (same as
     * LONG) — symmetric across sides. In backtest mode, {@code assetBalance}
     * is the synthetic {@code cashBalance / price} populated by the
     * coordinator; in live spot mode it is the real BTC inventory. Either
     * way the cap fraction acts as a hard ceiling on how much of "what we
     * could short" we actually deploy.
     *
     * <p>Returns ZERO on degenerate inputs (null context / non-positive
     * risk / missing inventory / stop=entry); callers fall back to legacy
     * sizing rather than killing the trade.
     */
    public BigDecimal calculateRiskBasedShortQty(
            EnrichedStrategyContext context,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal riskPct,
            BigDecimal maxAllocationPct) {
        if (!hasValidRiskInputs(context, entryPrice, stopLossPrice, riskPct)) return ZERO;

        // SHORT: stop must be strictly above entry. Mirror of the LONG
        // side-correctness guard — a wrong-sided stop here would also size
        // a guaranteed-loss trade off the |entry − stop| math. Return ZERO
        // so the strategy holds.
        if (stopLossPrice.compareTo(entryPrice) <= 0) return ZERO;

        BigDecimal stopDistance = stopLossPrice.subtract(entryPrice).abs();
        if (stopDistance.signum() <= 0) return ZERO;

        BigDecimal cash = safe(context.getCashBalance());
        if (cash.signum() <= 0) return ZERO;

        BigDecimal idealQty = cash.multiply(riskPct)
                .divide(stopDistance, 8, RoundingMode.HALF_UP);

        BigDecimal capFraction = resolveCapFraction(context, maxAllocationPct);
        if (capFraction.signum() <= 0) return ZERO;

        BigDecimal assetBalance = context.getAssetBalance();
        if (assetBalance == null || assetBalance.signum() <= 0) return ZERO;
        BigDecimal capQty = assetBalance.multiply(capFraction);

        return idealQty.min(capQty).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Returns the strategy's allocation as a fraction in (0, 1]. Reads
     * {@code AccountStrategy.capitalAllocationPct} (stored on the 0–100
     * scale per the schema) and divides by 100; caps the input at 100.
     *
     * <p>Returns ZERO when allocation is null, missing, or non-positive —
     * strict semantics: an unallocated strategy doesn't trade. The schema
     * has the column NOT NULL so this should only happen for partial
     * legacy rows or ad-hoc test contexts.
     */
    private BigDecimal resolveCapitalAllocationFraction(EnrichedStrategyContext context) {
        if (context == null || context.getAccountStrategy() == null) return ZERO;
        BigDecimal alloc = context.getAccountStrategy().getCapitalAllocationPct();
        if (alloc == null || alloc.signum() <= 0) return ZERO;
        BigDecimal capped = alloc.min(ONE_HUNDRED);
        return capped.divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);
    }

    public String resolveExecutionSource(EnrichedStrategyContext context) {
        String source = context.getExecutionMetadata("source", String.class);
        if (!StringUtils.hasText(source)) {
            return SOURCE_BACKTEST;
        }
        return source;
    }

    public boolean hasValue(BigDecimal value) {
        return value != null;
    }

    public BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}
