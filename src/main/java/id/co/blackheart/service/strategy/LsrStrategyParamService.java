package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.lsr.LsrParams;
import id.co.blackheart.dto.request.LsrParamUpdateRequest;
import id.co.blackheart.dto.response.LsrParamResponse;
import id.co.blackheart.model.StrategyParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages per-account-strategy LSR parameter overrides.
 *
 * <p>V29+ — this service is now a thin shim over {@link StrategyParamService}.
 * Storage lives in the unified {@code strategy_param} table (active preset per
 * account_strategy); this class adds two LSR-specific concerns:
 *
 * <ol>
 *   <li>Default merging — overlays the stored override map onto
 *       {@link LsrParams#defaults()} via {@link LsrParams#merge(Map)}.</li>
 *   <li>Backtest wizard overlay — when a backtest run is active and supplied
 *       its own tuning, those overrides win over both stored params and
 *       defaults, but only for the duration of the run (read-through, never
 *       cached).</li>
 * </ol>
 *
 * <p>Public API is unchanged so the existing {@code /api/v1/lsr-params}
 * controller and {@link LsrStrategyService} continue to work without edits.
 * Reset-to-defaults clears the active preset's overrides to {@code {}} rather
 * than deleting the row — preserves the "every account_strategy has an active
 * preset" invariant the promotion guard depends on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LsrStrategyParamService {

    private static final String STRATEGY_CODE = "LSR";

    private final StrategyParamService strategyParamService;

    // ── Read ──────────────────────────────────────────────────────────────────────

    /**
     * Returns the resolved (defaults + overrides + optional wizard overlay)
     * {@link LsrParams} for the given account_strategy.
     *
     * <p>Hot path of strategy execution; the unified service's Redis cache
     * serves the vast majority of calls.
     */
    public LsrParams getParams(UUID accountStrategyId) {
        Map<String, Object> wizardOverrides = BacktestParamOverrideContext.forStrategy(STRATEGY_CODE);

        if (accountStrategyId == null && wizardOverrides.isEmpty()) {
            return LsrParams.defaults();
        }

        Map<String, Object> stored = accountStrategyId == null
                ? new HashMap<>()
                : strategyParamService.resolveOverridesForStrategy(STRATEGY_CODE, accountStrategyId);

        if (wizardOverrides.isEmpty()) {
            return LsrParams.merge(stored);
        }
        Map<String, Object> layered = new HashMap<>(stored);
        layered.putAll(wizardOverrides);   // wizard wins on key collisions
        return LsrParams.merge(layered);
    }

    /**
     * Returns the full response DTO for the REST API (overrides + version + audit).
     */
    @Transactional(readOnly = true)
    public LsrParamResponse getParamResponse(UUID accountStrategyId) {
        Optional<StrategyParam> entity = strategyParamService.findActive(accountStrategyId);
        return entity.map(LsrStrategyParamService::buildResponse)
                .orElseGet(() -> LsrParamResponse.builder()
                        .accountStrategyId(accountStrategyId)
                        .hasCustomParams(false)
                        .overrides(Map.of())
                        .effectiveParams(LsrParams.defaults())
                        .version(null)
                        .updatedAt(null)
                        .build());
    }

    // ── Write ─────────────────────────────────────────────────────────────────────

    @Transactional
    public LsrParamResponse putParams(UUID accountStrategyId, LsrParamUpdateRequest request, String updatedBy) {
        Map<String, Object> newOverrides = buildOverrideMap(request);
        StrategyParam saved = strategyParamService.upsertActiveOverrides(
                accountStrategyId, newOverrides, updatedBy);
        log.info("LSR params PUT (via unified): accountStrategyId={} overrides={} by={}",
                accountStrategyId, newOverrides.keySet(), updatedBy);
        return buildResponse(saved);
    }

    @Transactional
    public LsrParamResponse patchParams(UUID accountStrategyId, LsrParamUpdateRequest request, String updatedBy) {
        Map<String, Object> incoming = buildOverrideMap(request);
        Map<String, Object> existing = strategyParamService.findActive(accountStrategyId)
                .map(StrategyParam::getParamOverrides)
                .map(HashMap<String, Object>::new)
                .orElseGet(HashMap::new);
        existing.putAll(incoming);

        StrategyParam saved = strategyParamService.upsertActiveOverrides(
                accountStrategyId, existing, updatedBy);
        log.info("LSR params PATCH (via unified): accountStrategyId={} merged keys={} by={}",
                accountStrategyId, existing.keySet(), updatedBy);
        return buildResponse(saved);
    }

    /**
     * Reset overrides to defaults. Preserves the active preset row (with
     * empty {@code {}} overrides) so the promotion-guard invariant — every
     * promotable strategy has an active preset — stays intact.
     */
    @Transactional
    public void resetToDefaults(UUID accountStrategyId, String updatedBy) {
        strategyParamService.upsertActiveOverrides(accountStrategyId, Map.of(), updatedBy);
        log.info("LSR params reset to defaults (via unified): accountStrategyId={} by={}",
                accountStrategyId, updatedBy);
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private static LsrParamResponse buildResponse(StrategyParam entity) {
        Map<String, Object> overrides = entity.getParamOverrides() == null
                ? Map.of()
                : entity.getParamOverrides();
        return LsrParamResponse.builder()
                .accountStrategyId(entity.getAccountStrategyId())
                .hasCustomParams(!overrides.isEmpty())
                .overrides(overrides)
                .effectiveParams(LsrParams.merge(overrides))
                .version(entity.getVersion())
                .updatedAt(entity.getUpdatedTime())
                .build();
    }

    /**
     * Converts a {@link LsrParamUpdateRequest} to a flat Map, including only non-null fields.
     */
    private Map<String, Object> buildOverrideMap(LsrParamUpdateRequest req) {
        Map<String, Object> m = new HashMap<>();
        if (req == null) return m;

        // Regime
        if (req.getAdxTrendingMin() != null)               m.put("adxTrendingMin", req.getAdxTrendingMin());
        if (req.getAdxCompressionMax() != null)            m.put("adxCompressionMax", req.getAdxCompressionMax());
        if (req.getAdxEntryMin() != null)                  m.put("adxEntryMin", req.getAdxEntryMin());
        if (req.getAdxEntryMax() != null)                  m.put("adxEntryMax", req.getAdxEntryMax());
        if (req.getAtrRatioExhaustion() != null)           m.put("atrRatioExhaustion", req.getAtrRatioExhaustion());
        if (req.getAtrRatioChaotic() != null)              m.put("atrRatioChaotic", req.getAtrRatioChaotic());
        if (req.getAtrRatioCompress() != null)             m.put("atrRatioCompress", req.getAtrRatioCompress());

        // Risk / exits
        if (req.getStopAtrBuffer() != null)                m.put("stopAtrBuffer", req.getStopAtrBuffer());
        if (req.getMaxRiskPct() != null)                   m.put("maxRiskPct", req.getMaxRiskPct());
        if (req.getTp1RLongSweep() != null)                m.put("tp1RLongSweep", req.getTp1RLongSweep());
        if (req.getTp1RLongContinuation() != null)         m.put("tp1RLongContinuation", req.getTp1RLongContinuation());
        if (req.getTp1RShort() != null)                    m.put("tp1RShort", req.getTp1RShort());
        if (req.getBeTriggerRLongSweep() != null)          m.put("beTriggerRLongSweep", req.getBeTriggerRLongSweep());
        if (req.getBeTriggerRLongContinuation() != null)   m.put("beTriggerRLongContinuation", req.getBeTriggerRLongContinuation());
        if (req.getBeTriggerRShort() != null)              m.put("beTriggerRShort", req.getBeTriggerRShort());
        if (req.getBeFeeBufferR() != null)                 m.put("beFeeBufferR", req.getBeFeeBufferR());
        if (req.getShortNotionalMultiplier() != null)      m.put("shortNotionalMultiplier", req.getShortNotionalMultiplier());
        if (req.getLongContinuationNotionalMultiplier() != null) m.put("longContinuationNotionalMultiplier", req.getLongContinuationNotionalMultiplier());

        // Time-stop bars
        if (req.getTimeStopBarsLongSweep() != null)        m.put("timeStopBarsLongSweep", req.getTimeStopBarsLongSweep());
        if (req.getTimeStopBarsLongContinuation() != null) m.put("timeStopBarsLongContinuation", req.getTimeStopBarsLongContinuation());
        if (req.getTimeStopBarsShort() != null)            m.put("timeStopBarsShort", req.getTimeStopBarsShort());

        // Time-stop min R
        if (req.getTimeStopMinRLongSweep() != null)        m.put("timeStopMinRLongSweep", req.getTimeStopMinRLongSweep());
        if (req.getTimeStopMinRLongContinuation() != null) m.put("timeStopMinRLongContinuation", req.getTimeStopMinRLongContinuation());
        if (req.getTimeStopMinRShort() != null)            m.put("timeStopMinRShort", req.getTimeStopMinRShort());

        // Long sweep
        if (req.getLongSweepMinAtr() != null)              m.put("longSweepMinAtr", req.getLongSweepMinAtr());
        if (req.getLongSweepMaxAtr() != null)              m.put("longSweepMaxAtr", req.getLongSweepMaxAtr());
        if (req.getLongSweepRsiMin() != null)              m.put("longSweepRsiMin", req.getLongSweepRsiMin());
        if (req.getLongSweepRsiMax() != null)              m.put("longSweepRsiMax", req.getLongSweepRsiMax());
        if (req.getLongSweepRvolMin() != null)             m.put("longSweepRvolMin", req.getLongSweepRvolMin());
        if (req.getLongSweepBodyMin() != null)             m.put("longSweepBodyMin", req.getLongSweepBodyMin());
        if (req.getLongSweepClvMin() != null)              m.put("longSweepClvMin", req.getLongSweepClvMin());
        if (req.getMinSignalScoreLongSweep() != null)      m.put("minSignalScoreLongSweep", req.getMinSignalScoreLongSweep());
        if (req.getMinConfidenceScoreLongSweep() != null)  m.put("minConfidenceScoreLongSweep", req.getMinConfidenceScoreLongSweep());

        // Long continuation
        if (req.getLongContRsiMin() != null)               m.put("longContRsiMin", req.getLongContRsiMin());
        if (req.getLongContRsiMax() != null)               m.put("longContRsiMax", req.getLongContRsiMax());
        if (req.getLongContRvolMin() != null)              m.put("longContRvolMin", req.getLongContRvolMin());
        if (req.getLongContBodyMin() != null)              m.put("longContBodyMin", req.getLongContBodyMin());
        if (req.getLongContClvMin() != null)               m.put("longContClvMin", req.getLongContClvMin());
        if (req.getLongContDonchianBufferAtr() != null)    m.put("longContDonchianBufferAtr", req.getLongContDonchianBufferAtr());
        if (req.getMinSignalScoreLongCont() != null)       m.put("minSignalScoreLongCont", req.getMinSignalScoreLongCont());
        if (req.getMinConfidenceScoreLongCont() != null)   m.put("minConfidenceScoreLongCont", req.getMinConfidenceScoreLongCont());

        // Short
        if (req.getShortSweepMinAtr() != null)             m.put("shortSweepMinAtr", req.getShortSweepMinAtr());
        if (req.getShortSweepMaxAtr() != null)             m.put("shortSweepMaxAtr", req.getShortSweepMaxAtr());
        if (req.getShortRsiMin() != null)                  m.put("shortRsiMin", req.getShortRsiMin());
        if (req.getShortRvolMin() != null)                 m.put("shortRvolMin", req.getShortRvolMin());
        if (req.getShortBodyMin() != null)                 m.put("shortBodyMin", req.getShortBodyMin());
        if (req.getShortClvMax() != null)                  m.put("shortClvMax", req.getShortClvMax());
        if (req.getMinSignalScoreShort() != null)          m.put("minSignalScoreShort", req.getMinSignalScoreShort());

        return m;
    }
}
