package id.co.blackheart.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Partial or full update request for VBO (Volatility Breakout) strategy parameters.
 * All fields are optional — null means "leave unchanged" (PATCH) or "remove override" (PUT).
 *
 * <p>Boolean gate flags ({@code requireKcSqueeze}, {@code requireDonchianBreak},
 * {@code requireTrendAlignment}) are nullable {@link Boolean} so an unset field
 * stays untouched on PATCH; PUT clears the override entirely.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VboParamUpdateRequest {

    // ── Compression detection (previous bar) ──────────────────────────────────
    @DecimalMin("0.001") @DecimalMax("0.50")
    private BigDecimal compressionBbWidthPctMax;

    @DecimalMin("5") @DecimalMax("60")
    private BigDecimal compressionAdxMax;

    private Boolean requireKcSqueeze;

    // ── Entry-bar ADX band ────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("80")
    private BigDecimal adxEntryMin;

    @DecimalMin("0") @DecimalMax("80")
    private BigDecimal adxEntryMax;

    // ── Breakout confirmation ─────────────────────────────────────────────────
    private Boolean requireDonchianBreak;

    private Boolean requireTrendAlignment;

    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal ema50SlopeMin;

    @DecimalMin("1") @DecimalMax("5")
    private BigDecimal atrExpansionMin;

    @DecimalMin("0.5") @DecimalMax("5")
    private BigDecimal rvolMin;

    // ── Breakout candle quality ───────────────────────────────────────────────
    @DecimalMin("0.1") @DecimalMax("1")
    private BigDecimal bodyRatioMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal clvMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal clvMax;

    // ── RSI sanity ────────────────────────────────────────────────────────────
    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal longRsiMax;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal shortRsiMin;

    // ── Risk / exits ──────────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("3")
    private BigDecimal stopAtrBuffer;

    @DecimalMin("0.001") @DecimalMax("0.20")
    private BigDecimal maxEntryRiskPct;

    @DecimalMin("0.5") @DecimalMax("10")
    private BigDecimal tp1R;

    // ── Position management ───────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal breakEvenR;

    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal runnerBreakEvenR;

    @DecimalMin("0.5") @DecimalMax("10")
    private BigDecimal runnerPhase2R;

    @DecimalMin("0.5") @DecimalMax("10")
    private BigDecimal runnerPhase3R;

    @DecimalMin("0.1") @DecimalMax("5")
    private BigDecimal runnerAtrPhase2;

    @DecimalMin("0.1") @DecimalMax("5")
    private BigDecimal runnerAtrPhase3;

    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal runnerLockPhase2R;

    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal runnerLockPhase3R;

    // ── Signal score ──────────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal minSignalScore;
}
