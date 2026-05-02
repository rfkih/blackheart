package id.co.blackheart.dto.funding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Resolved parameters for the {@code FCARRY} (funding-carry) strategy.
 *
 * <p>Strategy thesis: when perpetual-funding rates deviate far from their
 * trailing mean, the side <em>paying</em> the funding is overcrowded. Take
 * the contrarian side and collect the carry while the imbalance unwinds.
 *
 * <ul>
 *   <li>{@code fundingRateZ &gt; +entryZ} → longs are paying premium →
 *       enter SHORT.</li>
 *   <li>{@code fundingRateZ &lt; -entryZ} → shorts are paying premium →
 *       enter LONG.</li>
 *   <li>Exit when {@code |fundingRateZ| &lt; exitZ} (carry edge collapsed)
 *       or after {@code holdMaxBars} bars (time stop) or stop hits.</li>
 * </ul>
 *
 * <p>Defaults are deliberately conservative: 2σ entry, 0.5σ exit, 24-bar
 * time stop on a 1h timeframe (≈ 1 day), 1.5×ATR stop. Overrides via
 * {@link id.co.blackheart.model.StrategyParam}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundingCarryParams implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Builder.Default private BigDecimal entryZ        = new BigDecimal("2.0");
    @Builder.Default private BigDecimal exitZ         = new BigDecimal("0.5");
    @Builder.Default private Integer    holdMaxBars   = 24;
    @Builder.Default private BigDecimal atrStopMult   = new BigDecimal("1.5");

    /** Hard floor on |funding_rate_8h| (decimal, not bps). Below this the
     *  carry isn't worth the directional risk regardless of z. */
    @Builder.Default private BigDecimal minAbsRate8h  = new BigDecimal("0.0001");

    @Builder.Default private Boolean    allowLong     = Boolean.TRUE;
    @Builder.Default private Boolean    allowShort    = Boolean.TRUE;

    /** Optional trend filter — when true, also require side-aligned EMA
     *  trend before entry. Off by default; the strategy is a fundamental
     *  carry play, not a trend follower. */
    @Builder.Default private Boolean    requireTrendAlignment = Boolean.FALSE;

    public static FundingCarryParams defaults() {
        return FundingCarryParams.builder().build();
    }

    public static FundingCarryParams merge(Map<String, Object> overrides) {
        FundingCarryParams p = defaults();
        if (overrides == null || overrides.isEmpty()) return p;

        bd(overrides, "entryZ").ifPresent(p::setEntryZ);
        bd(overrides, "exitZ").ifPresent(p::setExitZ);
        intVal(overrides, "holdMaxBars").ifPresent(p::setHoldMaxBars);
        bd(overrides, "atrStopMult").ifPresent(p::setAtrStopMult);
        bd(overrides, "minAbsRate8h").ifPresent(p::setMinAbsRate8h);
        boolVal(overrides, "allowLong").ifPresent(p::setAllowLong);
        boolVal(overrides, "allowShort").ifPresent(p::setAllowShort);
        boolVal(overrides, "requireTrendAlignment").ifPresent(p::setRequireTrendAlignment);

        return p;
    }

    private static Optional<BigDecimal> bd(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return Optional.empty();
        if (v instanceof BigDecimal bd) return Optional.of(bd);
        if (v instanceof Number n) return Optional.of(BigDecimal.valueOf(n.doubleValue()));
        try {
            return Optional.of(new BigDecimal(v.toString().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return Optional.empty();
        if (v instanceof Integer i) return Optional.of(i);
        if (v instanceof Number n) return Optional.of(n.intValue());
        try {
            return Optional.of(Integer.parseInt(v.toString().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> boolVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return Optional.empty();
        if (v instanceof Boolean b) return Optional.of(b);
        String s = v.toString().trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return Optional.of(Boolean.TRUE);
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return Optional.of(Boolean.FALSE);
        return Optional.empty();
    }
}
