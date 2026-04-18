package id.co.blackheart.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

/**
 * Partial or full update request for LSR strategy parameters.
 *
 * <p>All fields are optional ({@code null} = leave unchanged for PATCH,
 * or remove override for PUT). Non-null values are validated and written
 * to the {@code param_overrides} JSONB column.
 *
 * <p>Validation bounds are intentionally wide — they guard against obviously
 * wrong values (negative RSI, zero TP ratio) without enforcing strategy opinions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LsrParamUpdateRequest {

    // ── Regime ───────────────────────────────────────────────────────────────────
    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal adxTrendingMin;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal adxCompressionMax;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal adxEntryMin;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal adxEntryMax;

    @DecimalMin("0.1") @DecimalMax("20")
    private BigDecimal atrRatioExhaustion;

    @DecimalMin("0.1") @DecimalMax("20")
    private BigDecimal atrRatioChaotic;

    @DecimalMin("0.01") @DecimalMax("5")
    private BigDecimal atrRatioCompress;

    // ── Risk / exits ─────────────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("2")
    private BigDecimal stopAtrBuffer;

    @DecimalMin("0.001") @DecimalMax("0.20")
    private BigDecimal maxRiskPct;

    @DecimalMin("0.1") @DecimalMax("20")
    private BigDecimal tp1RLongSweep;

    @DecimalMin("0.1") @DecimalMax("20")
    private BigDecimal tp1RLongContinuation;

    @DecimalMin("0.1") @DecimalMax("20")
    private BigDecimal tp1RShort;

    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal beTriggerRLongSweep;

    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal beTriggerRLongContinuation;

    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal beTriggerRShort;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal beFeeBufferR;

    @DecimalMin("0.1") @DecimalMax("2")
    private BigDecimal shortNotionalMultiplier;

    @DecimalMin("0.1") @DecimalMax("2")
    private BigDecimal longContinuationNotionalMultiplier;

    // ── Time-stop bars ───────────────────────────────────────────────────────────
    @Min(1)
    private Integer timeStopBarsLongSweep;

    @Min(1)
    private Integer timeStopBarsLongContinuation;

    @Min(1)
    private Integer timeStopBarsShort;

    // ── Time-stop minimum R ──────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal timeStopMinRLongSweep;

    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal timeStopMinRLongContinuation;

    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal timeStopMinRShort;

    // ── Long sweep ───────────────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal longSweepMinAtr;

    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal longSweepMaxAtr;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal longSweepRsiMin;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal longSweepRsiMax;

    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal longSweepRvolMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal longSweepBodyMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal longSweepClvMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal minSignalScoreLongSweep;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal minConfidenceScoreLongSweep;

    // ── Long continuation ────────────────────────────────────────────────────────
    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal longContRsiMin;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal longContRsiMax;

    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal longContRvolMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal longContBodyMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal longContClvMin;

    @DecimalMin("0") @DecimalMax("2")
    private BigDecimal longContDonchianBufferAtr;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal minSignalScoreLongCont;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal minConfidenceScoreLongCont;

    // ── Short ────────────────────────────────────────────────────────────────────
    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal shortSweepMinAtr;

    @DecimalMin("0") @DecimalMax("10")
    private BigDecimal shortSweepMaxAtr;

    @DecimalMin("1") @DecimalMax("100")
    private BigDecimal shortRsiMin;

    @DecimalMin("0") @DecimalMax("5")
    private BigDecimal shortRvolMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal shortBodyMin;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal shortClvMax;

    @DecimalMin("0") @DecimalMax("1")
    private BigDecimal minSignalScoreShort;
}
