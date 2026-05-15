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
import java.util.function.Supplier;

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
        putIfPresent(m, "adxTrendingMin",                  req::getAdxTrendingMin);
        putIfPresent(m, "adxCompressionMax",               req::getAdxCompressionMax);
        putIfPresent(m, "adxEntryMin",                     req::getAdxEntryMin);
        putIfPresent(m, "adxEntryMax",                     req::getAdxEntryMax);
        putIfPresent(m, "atrRatioExhaustion",              req::getAtrRatioExhaustion);
        putIfPresent(m, "atrRatioChaotic",                 req::getAtrRatioChaotic);
        putIfPresent(m, "atrRatioCompress",                req::getAtrRatioCompress);

        // Risk / exits
        putIfPresent(m, "stopAtrBuffer",                   req::getStopAtrBuffer);
        putIfPresent(m, "maxRiskPct",                      req::getMaxRiskPct);
        putIfPresent(m, "tp1RLongSweep",                   req::getTp1RLongSweep);
        putIfPresent(m, "tp1RLongContinuation",            req::getTp1RLongContinuation);
        putIfPresent(m, "tp1RShort",                       req::getTp1RShort);
        putIfPresent(m, "beTriggerRLongSweep",             req::getBeTriggerRLongSweep);
        putIfPresent(m, "beTriggerRLongContinuation",      req::getBeTriggerRLongContinuation);
        putIfPresent(m, "beTriggerRShort",                 req::getBeTriggerRShort);
        putIfPresent(m, "beFeeBufferR",                    req::getBeFeeBufferR);
        putIfPresent(m, "shortNotionalMultiplier",         req::getShortNotionalMultiplier);
        putIfPresent(m, "longContinuationNotionalMultiplier", req::getLongContinuationNotionalMultiplier);

        // Time-stop bars
        putIfPresent(m, "timeStopBarsLongSweep",           req::getTimeStopBarsLongSweep);
        putIfPresent(m, "timeStopBarsLongContinuation",    req::getTimeStopBarsLongContinuation);
        putIfPresent(m, "timeStopBarsShort",               req::getTimeStopBarsShort);

        // Time-stop min R
        putIfPresent(m, "timeStopMinRLongSweep",           req::getTimeStopMinRLongSweep);
        putIfPresent(m, "timeStopMinRLongContinuation",    req::getTimeStopMinRLongContinuation);
        putIfPresent(m, "timeStopMinRShort",               req::getTimeStopMinRShort);

        // Long sweep
        putIfPresent(m, "longSweepMinAtr",                 req::getLongSweepMinAtr);
        putIfPresent(m, "longSweepMaxAtr",                 req::getLongSweepMaxAtr);
        putIfPresent(m, "longSweepRsiMin",                 req::getLongSweepRsiMin);
        putIfPresent(m, "longSweepRsiMax",                 req::getLongSweepRsiMax);
        putIfPresent(m, "longSweepRvolMin",                req::getLongSweepRvolMin);
        putIfPresent(m, "longSweepBodyMin",                req::getLongSweepBodyMin);
        putIfPresent(m, "longSweepClvMin",                 req::getLongSweepClvMin);
        putIfPresent(m, "minSignalScoreLongSweep",         req::getMinSignalScoreLongSweep);
        putIfPresent(m, "minConfidenceScoreLongSweep",     req::getMinConfidenceScoreLongSweep);

        // Long continuation
        putIfPresent(m, "longContRsiMin",                  req::getLongContRsiMin);
        putIfPresent(m, "longContRsiMax",                  req::getLongContRsiMax);
        putIfPresent(m, "longContRvolMin",                 req::getLongContRvolMin);
        putIfPresent(m, "longContBodyMin",                 req::getLongContBodyMin);
        putIfPresent(m, "longContClvMin",                  req::getLongContClvMin);
        putIfPresent(m, "longContDonchianBufferAtr",       req::getLongContDonchianBufferAtr);
        putIfPresent(m, "minSignalScoreLongCont",          req::getMinSignalScoreLongCont);
        putIfPresent(m, "minConfidenceScoreLongCont",      req::getMinConfidenceScoreLongCont);

        // Short
        putIfPresent(m, "shortSweepMinAtr",                req::getShortSweepMinAtr);
        putIfPresent(m, "shortSweepMaxAtr",                req::getShortSweepMaxAtr);
        putIfPresent(m, "shortRsiMin",                     req::getShortRsiMin);
        putIfPresent(m, "shortRvolMin",                    req::getShortRvolMin);
        putIfPresent(m, "shortBodyMin",                    req::getShortBodyMin);
        putIfPresent(m, "shortClvMax",                     req::getShortClvMax);
        putIfPresent(m, "minSignalScoreShort",             req::getMinSignalScoreShort);

        return m;
    }

    private static <T> void putIfPresent(Map<String, Object> m, String key, Supplier<T> getter) {
        T value = getter.get();
        if (value != null) m.put(key, value);
    }
}
