package id.co.blackheart.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Partial or full update request for VCB strategy parameters.
 * All fields are optional — null means "leave unchanged" (PATCH) or "remove override" (PUT).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VcbParamUpdateRequest {

    // ── Compression ───────────────────────────────────────────────────────────
    @DecimalMin("0.1") @DecimalMax("1.5")
    private BigDecimal squeezeKcTolerance;

    @DecimalMin("0.1") @DecimalMax("5")
    private BigDecimal atrRatioCompressMax;

    @DecimalMin("0.01") @DecimalMax("1")
    private BigDecimal erCompressMax;

    // ── Breakout ──────────────────────────────────────────────────────────────
    @DecimalMin("0.5") @DecimalMax("5")
    private BigDecimal relVolBreakoutMin;

    @DecimalMin("1") @DecimalMax("10")
    private BigDecimal relVolBreakoutMax;

    @DecimalMin("0.1") @DecimalMax("1")
    private BigDecimal bodyRatioBreakoutMin;

    // ── 4H bias ───────────────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal biasErMin;

    // ── Entry filters ─────────────────────────────────────────────────────────
    @DecimalMin("10") @DecimalMax("80")
    private BigDecimal adxEntryMax;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal longRsiMin;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal shortRsiMax;

    @DecimalMin("0") @DecimalMax("20")
    private BigDecimal longDiSpreadMin;

    @DecimalMin("0") @DecimalMax("20")
    private BigDecimal shortDiSpreadMin;

    // ── Risk / exits ──────────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("3")
    private BigDecimal stopAtrBuffer;

    @DecimalMin("0.5") @DecimalMax("10")
    private BigDecimal tp1R;

    @DecimalMin("0.001") @DecimalMax("0.20")
    private BigDecimal maxEntryRiskPct;

    // ── Runner phases ─────────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal runnerHalfR;

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
