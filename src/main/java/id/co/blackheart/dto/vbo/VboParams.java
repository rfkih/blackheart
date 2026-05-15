package id.co.blackheart.dto.vbo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Resolved VBO (Volatility Breakout) strategy parameters for a single execution cycle.
 *
 * <p>Mirrors the LSR / VCB params pattern: {@link #defaults()} returns a
 * fully-defaulted instance carrying the current research-tuned numbers (v0_2);
 * {@link #merge(Map)} returns defaults overlaid with the supplied overrides;
 * {@link #applyOverrides(Map)} mutates this instance in-place, returning the
 * count of keys actually applied.
 *
 * <p>Override-map keys must match the field names exactly. Numeric values may
 * arrive as {@link BigDecimal}, any {@link Number}, {@link Boolean} (treated as
 * 0 / 1), or string. Booleans (gate flags) accept {@link Boolean} or any
 * non-zero {@link Number}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VboParams implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── Compression detection (previous bar) ──────────────────────────────────
    @Builder.Default private BigDecimal compressionBbWidthPctMax = new BigDecimal("0.05");
    @Builder.Default private BigDecimal compressionAdxMax        = new BigDecimal("25");
    @Builder.Default private boolean    requireKcSqueeze         = false;

    // ── Entry-bar ADX band ────────────────────────────────────────────────────
    @Builder.Default private BigDecimal adxEntryMin              = new BigDecimal("15");
    @Builder.Default private BigDecimal adxEntryMax              = new BigDecimal("22");

    // ── Breakout confirmation ─────────────────────────────────────────────────
    @Builder.Default private boolean    requireDonchianBreak     = false;
    @Builder.Default private boolean    requireTrendAlignment    = false;
    @Builder.Default private BigDecimal ema50SlopeMin            = new BigDecimal("0");
    @Builder.Default private boolean    requireSlope200Gate      = false;
    @Builder.Default private BigDecimal slope200Min              = new BigDecimal("0");
    @Builder.Default private BigDecimal atrExpansionMin          = new BigDecimal("1.30");
    @Builder.Default private BigDecimal rvolMin                  = new BigDecimal("1.20");

    // ── Breakout candle quality ───────────────────────────────────────────────
    @Builder.Default private BigDecimal bodyRatioMin             = new BigDecimal("0.45");
    @Builder.Default private BigDecimal clvMin                   = new BigDecimal("0.90");
    @Builder.Default private BigDecimal clvMax                   = new BigDecimal("1.00");

    // ── RSI sanity ────────────────────────────────────────────────────────────
    @Builder.Default private BigDecimal longRsiMax               = new BigDecimal("78");
    @Builder.Default private BigDecimal shortRsiMin              = new BigDecimal("22");

    // ── Risk / exits ──────────────────────────────────────────────────────────
    @Builder.Default private BigDecimal stopAtrBuffer            = new BigDecimal("0.40");
    @Builder.Default private BigDecimal maxEntryRiskPct          = new BigDecimal("0.04");
    @Builder.Default private BigDecimal tp1R                     = new BigDecimal("1.50");

    // ── Position management ───────────────────────────────────────────────────
    @Builder.Default private BigDecimal breakEvenR               = new BigDecimal("1.00");
    @Builder.Default private BigDecimal runnerBreakEvenR         = new BigDecimal("1.00");
    @Builder.Default private BigDecimal runnerPhase2R            = new BigDecimal("2.00");
    @Builder.Default private BigDecimal runnerPhase3R            = new BigDecimal("3.50");
    @Builder.Default private BigDecimal runnerAtrPhase2          = new BigDecimal("2.00");
    @Builder.Default private BigDecimal runnerAtrPhase3          = new BigDecimal("1.50");
    @Builder.Default private BigDecimal runnerLockPhase2R        = new BigDecimal("1.00");
    @Builder.Default private BigDecimal runnerLockPhase3R        = new BigDecimal("2.50");

    // ── Score ─────────────────────────────────────────────────────────────────
    @Builder.Default private BigDecimal minSignalScore           = new BigDecimal("0.80");

    // ── Factories ─────────────────────────────────────────────────────────────

    public static VboParams defaults() {
        return VboParams.builder().build();
    }

    public static VboParams merge(Map<String, Object> overrides) {
        VboParams p = defaults();
        p.applyOverrides(overrides);
        return p;
    }

    /**
     * Apply the given override map onto this instance via explicit setters.
     *
     * <p>Explicit setters (rather than a Jackson {@code convertValue} round-trip)
     * are used because the round-trip silently dropped overrides in the TPR sweep
     * pipeline — inner-map BigDecimal values deserialised as Double. Verbose, but
     * never loses an override.
     *
     * @return number of keys that matched a known field. Unknown keys are
     *         logged and ignored so a typo doesn't nuke a sweep.
     */
    public int applyOverrides(Map<String, Object> overrides) {
        if (CollectionUtils.isEmpty(overrides)) return 0;
        int applied = 0;
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            if (applyOverride(e.getKey(), e.getValue())) applied++;
        }
        return applied;
    }

    /**
     * Dispatches an override to the decimal- or boolean-typed setter group.
     * Behaviour matches the prior monolithic switch:
     *   • returns true only when the key is recognised AND the value parsed;
     *   • unparseable values for a known key skip the override (return false);
     *   • unknown keys return false.
     * Booleans-as-numbers and numbers-as-booleans cross-coercion is preserved
     * via {@link #bd(Object)} / {@link #bool(Object)}.
     */
    private boolean applyOverride(String key, Object raw) {
        return applyDecimalOverride(key, raw) || applyBooleanOverride(key, raw);
    }

    private boolean applyDecimalOverride(String key, Object raw) {
        BigDecimal v = bd(raw);
        if (v == null) return false;
        switch (key) {
            case "compressionBbWidthPctMax" -> this.compressionBbWidthPctMax = v;
            case "compressionAdxMax"        -> this.compressionAdxMax        = v;
            case "adxEntryMin"              -> this.adxEntryMin              = v;
            case "adxEntryMax"              -> this.adxEntryMax              = v;
            case "ema50SlopeMin"            -> this.ema50SlopeMin            = v;
            case "slope200Min"              -> this.slope200Min              = v;
            case "atrExpansionMin"          -> this.atrExpansionMin          = v;
            case "rvolMin"                  -> this.rvolMin                  = v;
            case "bodyRatioMin"             -> this.bodyRatioMin             = v;
            case "clvMin"                   -> this.clvMin                   = v;
            case "clvMax"                   -> this.clvMax                   = v;
            case "longRsiMax"               -> this.longRsiMax               = v;
            case "shortRsiMin"              -> this.shortRsiMin              = v;
            case "stopAtrBuffer"            -> this.stopAtrBuffer            = v;
            case "maxEntryRiskPct"          -> this.maxEntryRiskPct          = v;
            case "tp1R"                     -> this.tp1R                     = v;
            case "breakEvenR"               -> this.breakEvenR               = v;
            case "runnerBreakEvenR"         -> this.runnerBreakEvenR         = v;
            case "runnerPhase2R"            -> this.runnerPhase2R            = v;
            case "runnerPhase3R"            -> this.runnerPhase3R            = v;
            case "runnerAtrPhase2"          -> this.runnerAtrPhase2          = v;
            case "runnerAtrPhase3"          -> this.runnerAtrPhase3          = v;
            case "runnerLockPhase2R"        -> this.runnerLockPhase2R        = v;
            case "runnerLockPhase3R"        -> this.runnerLockPhase3R        = v;
            case "minSignalScore"           -> this.minSignalScore           = v;
            default -> { return false; }
        }
        return true;
    }

    private boolean applyBooleanOverride(String key, Object raw) {
        Boolean b = bool(raw);
        if (b == null) return false;
        switch (key) {
            case "requireKcSqueeze"      -> this.requireKcSqueeze      = b;
            case "requireDonchianBreak"  -> this.requireDonchianBreak  = b;
            case "requireTrendAlignment" -> this.requireTrendAlignment = b;
            case "requireSlope200Gate"   -> this.requireSlope200Gate   = b;
            default -> { return false; }
        }
        return true;
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (v instanceof Boolean b) return b.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
        try { return new BigDecimal(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Lookup-style boolean coercion. Returns {@code null} when the input
     * can't be interpreted as a boolean (or a numeric truth value), so the
     * caller can distinguish "unparseable / skip override" from {@code false}.
     * The {@link #applyOverrides(Map)} contract relies on this null signal.
     */
    @SuppressWarnings("java:S2447")
    private static Boolean bool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n)  return n.doubleValue() != 0d;
        String s = v.toString().trim();
        if (s.equalsIgnoreCase("true"))  return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        try { return new BigDecimal(s).compareTo(BigDecimal.ZERO) != 0; }
        catch (NumberFormatException e) { return null; }
    }
}
