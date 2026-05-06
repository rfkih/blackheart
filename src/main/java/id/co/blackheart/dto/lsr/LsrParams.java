package id.co.blackheart.dto.lsr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Resolved LSR strategy parameters for a single execution cycle.
 *
 * <p>This is an <em>immutable-intent</em> value object produced by merging user overrides
 * (stored in {@link id.co.blackheart.model.LsrStrategyParam}) on top of the canonical defaults.
 * All defaults mirror the previous static constants in {@link id.co.blackheart.service.strategy.LsrStrategyService}.
 *
 * <p>Use {@link #defaults()} to obtain a fully-defaulted instance, or
 * {@link #merge(Map)} to produce an instance that applies user overrides on top of defaults.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsrParams implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── Regime / volatility thresholds ──────────────────────────────────────────
    @Builder.Default private BigDecimal adxTrendingMin               = new BigDecimal("22");
    @Builder.Default private BigDecimal adxCompressionMax            = new BigDecimal("18");
    @Builder.Default private BigDecimal adxEntryMin                  = new BigDecimal("15");
    @Builder.Default private BigDecimal adxEntryMax                  = new BigDecimal("30");
    @Builder.Default private BigDecimal atrRatioExhaustion           = new BigDecimal("2.50");
    @Builder.Default private BigDecimal atrRatioChaotic              = new BigDecimal("1.80");
    @Builder.Default private BigDecimal atrRatioCompress             = new BigDecimal("0.70");

    // ── Risk / exits ─────────────────────────────────────────────────────────────
    @Builder.Default private BigDecimal stopAtrBuffer                = new BigDecimal("0.20");
    @Builder.Default private BigDecimal maxRiskPct                   = new BigDecimal("0.03");
    @Builder.Default private BigDecimal tp1RLongSweep                = new BigDecimal("2.00");
    @Builder.Default private BigDecimal tp1RLongContinuation         = new BigDecimal("1.80");
    @Builder.Default private BigDecimal tp1RShort                    = new BigDecimal("1.60");
    @Builder.Default private BigDecimal beTriggerRLongSweep          = new BigDecimal("0.90");
    @Builder.Default private BigDecimal beTriggerRLongContinuation   = new BigDecimal("0.80");
    @Builder.Default private BigDecimal beTriggerRShort              = new BigDecimal("0.60");
    @Builder.Default private BigDecimal beFeeBufferR                 = new BigDecimal("0.20");
    @Builder.Default private BigDecimal shortNotionalMultiplier      = new BigDecimal("0.70");
    @Builder.Default private BigDecimal longContinuationNotionalMultiplier = new BigDecimal("0.85");

    // ── Time-stop bars ───────────────────────────────────────────────────────────
    @Builder.Default private Integer    timeStopBarsLongSweep        = 14;
    @Builder.Default private Integer    timeStopBarsLongContinuation = 12;
    @Builder.Default private Integer    timeStopBarsShort            = 10;

    // ── Time-stop minimum R ──────────────────────────────────────────────────────
    @Builder.Default private BigDecimal timeStopMinRLongSweep        = new BigDecimal("0.60");
    @Builder.Default private BigDecimal timeStopMinRLongContinuation = new BigDecimal("0.50");
    @Builder.Default private BigDecimal timeStopMinRShort            = new BigDecimal("0.50");

    // ── Long sweep reclaim ───────────────────────────────────────────────────────
    @Builder.Default private BigDecimal longSweepMinAtr              = new BigDecimal("0.15");
    @Builder.Default private BigDecimal longSweepMaxAtr              = new BigDecimal("2.20");
    @Builder.Default private BigDecimal longSweepRsiMin              = new BigDecimal("35");
    @Builder.Default private BigDecimal longSweepRsiMax              = new BigDecimal("48");
    @Builder.Default private BigDecimal longSweepRvolMin             = new BigDecimal("0.95");
    @Builder.Default private BigDecimal longSweepBodyMin             = new BigDecimal("0.28");
    @Builder.Default private BigDecimal longSweepClvMin              = new BigDecimal("0.58");
    @Builder.Default private BigDecimal minSignalScoreLongSweep      = new BigDecimal("0.72");
    @Builder.Default private BigDecimal minConfidenceScoreLongSweep  = new BigDecimal("0.75");

    // ── Long continuation reclaim ────────────────────────────────────────────────
    @Builder.Default private BigDecimal longContRsiMin               = new BigDecimal("38");
    @Builder.Default private BigDecimal longContRsiMax               = new BigDecimal("50");
    @Builder.Default private BigDecimal longContRvolMin              = new BigDecimal("0.90");
    @Builder.Default private BigDecimal longContBodyMin              = new BigDecimal("0.24");
    @Builder.Default private BigDecimal longContClvMin               = new BigDecimal("0.60");
    @Builder.Default private BigDecimal longContDonchianBufferAtr    = new BigDecimal("0.10");
    @Builder.Default private BigDecimal minSignalScoreLongCont       = new BigDecimal("0.72");
    @Builder.Default private BigDecimal minConfidenceScoreLongCont   = new BigDecimal("0.75");

    // ── Short exhaustion ─────────────────────────────────────────────────────────
    @Builder.Default private BigDecimal shortSweepMinAtr             = new BigDecimal("0.25");
    @Builder.Default private BigDecimal shortSweepMaxAtr             = new BigDecimal("1.80");
    @Builder.Default private BigDecimal shortRsiMin                  = new BigDecimal("65");
    @Builder.Default private BigDecimal shortRvolMin                 = new BigDecimal("1.20");
    @Builder.Default private BigDecimal shortBodyMin                 = new BigDecimal("0.42");
    @Builder.Default private BigDecimal shortClvMax                  = new BigDecimal("0.30");
    @Builder.Default private BigDecimal minSignalScoreShort          = new BigDecimal("0.60");

    // ── Factory ──────────────────────────────────────────────────────────────────

    /** Returns a fully-defaulted {@link LsrParams} instance. */
    public static LsrParams defaults() {
        return LsrParams.builder().build();
    }

    /**
     * Merges user-supplied overrides on top of defaults.
     *
     * <p>Keys in {@code overrides} must match the camelCase field names of this class
     * (e.g. {@code "adxTrendingMin"}, {@code "timeStopBarsShort"}).
     * Unknown keys are silently ignored. Values may be {@link BigDecimal}, {@link Number},
     * or a {@link String} parseable as the target type.
     *
     * @param overrides nullable map of param overrides from the DB
     * @return new {@link LsrParams} with defaults overridden by any present values
     */
    public static LsrParams merge(Map<String, Object> overrides) {
        LsrParams p = defaults();
        if (CollectionUtils.isEmpty(overrides)) return p;

        // Regime
        bd(overrides, "adxTrendingMin").ifPresent(p::setAdxTrendingMin);
        bd(overrides, "adxCompressionMax").ifPresent(p::setAdxCompressionMax);
        bd(overrides, "adxEntryMin").ifPresent(p::setAdxEntryMin);
        bd(overrides, "adxEntryMax").ifPresent(p::setAdxEntryMax);
        bd(overrides, "atrRatioExhaustion").ifPresent(p::setAtrRatioExhaustion);
        bd(overrides, "atrRatioChaotic").ifPresent(p::setAtrRatioChaotic);
        bd(overrides, "atrRatioCompress").ifPresent(p::setAtrRatioCompress);

        // Risk / exits
        bd(overrides, "stopAtrBuffer").ifPresent(p::setStopAtrBuffer);
        bd(overrides, "maxRiskPct").ifPresent(p::setMaxRiskPct);
        bd(overrides, "tp1RLongSweep").ifPresent(p::setTp1RLongSweep);
        bd(overrides, "tp1RLongContinuation").ifPresent(p::setTp1RLongContinuation);
        bd(overrides, "tp1RShort").ifPresent(p::setTp1RShort);
        bd(overrides, "beTriggerRLongSweep").ifPresent(p::setBeTriggerRLongSweep);
        bd(overrides, "beTriggerRLongContinuation").ifPresent(p::setBeTriggerRLongContinuation);
        bd(overrides, "beTriggerRShort").ifPresent(p::setBeTriggerRShort);
        bd(overrides, "beFeeBufferR").ifPresent(p::setBeFeeBufferR);
        bd(overrides, "shortNotionalMultiplier").ifPresent(p::setShortNotionalMultiplier);
        bd(overrides, "longContinuationNotionalMultiplier").ifPresent(p::setLongContinuationNotionalMultiplier);

        // Time-stop bars (int)
        intVal(overrides, "timeStopBarsLongSweep").ifPresent(p::setTimeStopBarsLongSweep);
        intVal(overrides, "timeStopBarsLongContinuation").ifPresent(p::setTimeStopBarsLongContinuation);
        intVal(overrides, "timeStopBarsShort").ifPresent(p::setTimeStopBarsShort);

        // Time-stop minimum R
        bd(overrides, "timeStopMinRLongSweep").ifPresent(p::setTimeStopMinRLongSweep);
        bd(overrides, "timeStopMinRLongContinuation").ifPresent(p::setTimeStopMinRLongContinuation);
        bd(overrides, "timeStopMinRShort").ifPresent(p::setTimeStopMinRShort);

        // Long sweep
        bd(overrides, "longSweepMinAtr").ifPresent(p::setLongSweepMinAtr);
        bd(overrides, "longSweepMaxAtr").ifPresent(p::setLongSweepMaxAtr);
        bd(overrides, "longSweepRsiMin").ifPresent(p::setLongSweepRsiMin);
        bd(overrides, "longSweepRsiMax").ifPresent(p::setLongSweepRsiMax);
        bd(overrides, "longSweepRvolMin").ifPresent(p::setLongSweepRvolMin);
        bd(overrides, "longSweepBodyMin").ifPresent(p::setLongSweepBodyMin);
        bd(overrides, "longSweepClvMin").ifPresent(p::setLongSweepClvMin);
        bd(overrides, "minSignalScoreLongSweep").ifPresent(p::setMinSignalScoreLongSweep);
        bd(overrides, "minConfidenceScoreLongSweep").ifPresent(p::setMinConfidenceScoreLongSweep);

        // Long continuation
        bd(overrides, "longContRsiMin").ifPresent(p::setLongContRsiMin);
        bd(overrides, "longContRsiMax").ifPresent(p::setLongContRsiMax);
        bd(overrides, "longContRvolMin").ifPresent(p::setLongContRvolMin);
        bd(overrides, "longContBodyMin").ifPresent(p::setLongContBodyMin);
        bd(overrides, "longContClvMin").ifPresent(p::setLongContClvMin);
        bd(overrides, "longContDonchianBufferAtr").ifPresent(p::setLongContDonchianBufferAtr);
        bd(overrides, "minSignalScoreLongCont").ifPresent(p::setMinSignalScoreLongCont);
        bd(overrides, "minConfidenceScoreLongCont").ifPresent(p::setMinConfidenceScoreLongCont);

        // Short
        bd(overrides, "shortSweepMinAtr").ifPresent(p::setShortSweepMinAtr);
        bd(overrides, "shortSweepMaxAtr").ifPresent(p::setShortSweepMaxAtr);
        bd(overrides, "shortRsiMin").ifPresent(p::setShortRsiMin);
        bd(overrides, "shortRvolMin").ifPresent(p::setShortRvolMin);
        bd(overrides, "shortBodyMin").ifPresent(p::setShortBodyMin);
        bd(overrides, "shortClvMax").ifPresent(p::setShortClvMax);
        bd(overrides, "minSignalScoreShort").ifPresent(p::setMinSignalScoreShort);

        return p;
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private static Optional<BigDecimal> bd(Map<String, Object> m, String key) {
        Object v = m.get(key);
        switch (v) {
            case null -> {
                return Optional.empty();
            }
            case BigDecimal bd -> {
                return Optional.of(bd);
            }
            case Number n -> {
                return Optional.of(BigDecimal.valueOf(n.doubleValue()));
            }
            default -> {
            }
        }
        try {
            return Optional.of(new BigDecimal(v.toString().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        switch (v) {
            case null -> {
                return Optional.empty();
            }
            case Integer i -> {
                return Optional.of(i);
            }
            case Number n -> {
                return Optional.of(n.intValue());
            }
            default -> {
            }
        }
        try {
            return Optional.of(Integer.parseInt(v.toString().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
