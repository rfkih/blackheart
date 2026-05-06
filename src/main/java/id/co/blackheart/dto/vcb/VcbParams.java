package id.co.blackheart.dto.vcb;

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
 * Resolved VCB strategy parameters for a single execution cycle.
 *
 * <p>Use {@link #defaults()} to obtain a fully-defaulted instance, or
 * {@link #merge(Map)} to produce an instance that applies user overrides on top of defaults.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VcbParams implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── Compression thresholds ────────────────────────────────────────────────
    @Builder.Default private BigDecimal squeezeKcTolerance   = new BigDecimal("0.95");
    @Builder.Default private BigDecimal atrRatioCompressMax  = new BigDecimal("1.00");
    @Builder.Default private BigDecimal erCompressMax        = new BigDecimal("0.30");

    // ── Breakout thresholds ───────────────────────────────────────────────────
    @Builder.Default private BigDecimal relVolBreakoutMin    = new BigDecimal("1.30");
    @Builder.Default private BigDecimal relVolBreakoutMax    = new BigDecimal("2.50");
    @Builder.Default private BigDecimal bodyRatioBreakoutMin = new BigDecimal("0.45");

    // ── 4H bias threshold ─────────────────────────────────────────────────────
    @Builder.Default private BigDecimal biasErMin            = new BigDecimal("0.05");

    // ── Entry filters ─────────────────────────────────────────────────────────
    @Builder.Default private BigDecimal adxEntryMax          = new BigDecimal("35");
    @Builder.Default private BigDecimal longRsiMin           = new BigDecimal("62");
    @Builder.Default private BigDecimal shortRsiMax          = new BigDecimal("40");
    @Builder.Default private BigDecimal longDiSpreadMin      = new BigDecimal("2.0");
    @Builder.Default private BigDecimal shortDiSpreadMin     = new BigDecimal("2.0");

    // ── Risk / exits ─────────────────────────────────────────────────────────
    @Builder.Default private BigDecimal stopAtrBuffer        = new BigDecimal("0.60");
    @Builder.Default private BigDecimal tp1R                 = new BigDecimal("2.80");
    @Builder.Default private BigDecimal maxEntryRiskPct      = new BigDecimal("0.04");

    // ── Runner trail phases ───────────────────────────────────────────────────
    @Builder.Default private BigDecimal runnerHalfR          = new BigDecimal("0.90");
    @Builder.Default private BigDecimal runnerBreakEvenR     = new BigDecimal("1.50");
    @Builder.Default private BigDecimal runnerPhase2R        = new BigDecimal("2.80");
    @Builder.Default private BigDecimal runnerPhase3R        = new BigDecimal("4.00");
    @Builder.Default private BigDecimal runnerAtrPhase2      = new BigDecimal("1.80");
    @Builder.Default private BigDecimal runnerAtrPhase3      = new BigDecimal("1.20");
    @Builder.Default private BigDecimal runnerLockPhase2R    = new BigDecimal("1.20");
    @Builder.Default private BigDecimal runnerLockPhase3R    = new BigDecimal("2.80");

    // ── Signal score threshold ────────────────────────────────────────────────
    @Builder.Default private BigDecimal minSignalScore       = new BigDecimal("0.60");

    // ── Factory ──────────────────────────────────────────────────────────────

    public static VcbParams defaults() {
        return VcbParams.builder().build();
    }

    public static VcbParams merge(Map<String, Object> overrides) {
        VcbParams p = defaults();
        if (CollectionUtils.isEmpty(overrides)) return p;

        // Compression
        bd(overrides, "squeezeKcTolerance").ifPresent(p::setSqueezeKcTolerance);
        bd(overrides, "atrRatioCompressMax").ifPresent(p::setAtrRatioCompressMax);
        bd(overrides, "erCompressMax").ifPresent(p::setErCompressMax);

        // Breakout
        bd(overrides, "relVolBreakoutMin").ifPresent(p::setRelVolBreakoutMin);
        bd(overrides, "relVolBreakoutMax").ifPresent(p::setRelVolBreakoutMax);
        bd(overrides, "bodyRatioBreakoutMin").ifPresent(p::setBodyRatioBreakoutMin);

        // Bias
        bd(overrides, "biasErMin").ifPresent(p::setBiasErMin);

        // Entry filters
        bd(overrides, "adxEntryMax").ifPresent(p::setAdxEntryMax);
        bd(overrides, "longRsiMin").ifPresent(p::setLongRsiMin);
        bd(overrides, "shortRsiMax").ifPresent(p::setShortRsiMax);
        bd(overrides, "longDiSpreadMin").ifPresent(p::setLongDiSpreadMin);
        bd(overrides, "shortDiSpreadMin").ifPresent(p::setShortDiSpreadMin);

        // Risk / exits
        bd(overrides, "stopAtrBuffer").ifPresent(p::setStopAtrBuffer);
        bd(overrides, "tp1R").ifPresent(p::setTp1R);
        bd(overrides, "maxEntryRiskPct").ifPresent(p::setMaxEntryRiskPct);

        // Runner phases
        bd(overrides, "runnerHalfR").ifPresent(p::setRunnerHalfR);
        bd(overrides, "runnerBreakEvenR").ifPresent(p::setRunnerBreakEvenR);
        bd(overrides, "runnerPhase2R").ifPresent(p::setRunnerPhase2R);
        bd(overrides, "runnerPhase3R").ifPresent(p::setRunnerPhase3R);
        bd(overrides, "runnerAtrPhase2").ifPresent(p::setRunnerAtrPhase2);
        bd(overrides, "runnerAtrPhase3").ifPresent(p::setRunnerAtrPhase3);
        bd(overrides, "runnerLockPhase2R").ifPresent(p::setRunnerLockPhase2R);
        bd(overrides, "runnerLockPhase3R").ifPresent(p::setRunnerLockPhase3R);

        // Score
        bd(overrides, "minSignalScore").ifPresent(p::setMinSignalScore);

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
}
