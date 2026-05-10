package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.request.VcbParamUpdateRequest;
import id.co.blackheart.dto.response.VcbParamResponse;
import id.co.blackheart.dto.vcb.VcbParams;
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
 * VCB parameter shim over {@link StrategyParamService} (V29+ unified table).
 * See {@link LsrStrategyParamService} for the architecture rationale.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VcbStrategyParamService {

    private static final String STRATEGY_CODE = "VCB";

    private final StrategyParamService strategyParamService;

    // ── Read ──────────────────────────────────────────────────────────────────

    public VcbParams getParams(UUID accountStrategyId) {
        Map<String, Object> wizardOverrides = BacktestParamOverrideContext.forStrategy(STRATEGY_CODE);

        if (accountStrategyId == null && wizardOverrides.isEmpty()) {
            return VcbParams.defaults();
        }

        Map<String, Object> stored = accountStrategyId == null
                ? new HashMap<>()
                : strategyParamService.resolveOverridesForStrategy(STRATEGY_CODE, accountStrategyId);

        if (wizardOverrides.isEmpty()) {
            return VcbParams.merge(stored);
        }
        Map<String, Object> layered = new HashMap<>(stored);
        layered.putAll(wizardOverrides);
        return VcbParams.merge(layered);
    }

    @Transactional(readOnly = true)
    public VcbParamResponse getParamResponse(UUID accountStrategyId) {
        Optional<StrategyParam> entity = strategyParamService.findActive(accountStrategyId);
        return entity.map(VcbStrategyParamService::buildResponse)
                .orElseGet(() -> VcbParamResponse.builder()
                        .accountStrategyId(accountStrategyId)
                        .hasCustomParams(false)
                        .overrides(Map.of())
                        .effectiveParams(VcbParams.defaults())
                        .version(null)
                        .updatedAt(null)
                        .build());
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Transactional
    public VcbParamResponse putParams(UUID accountStrategyId, VcbParamUpdateRequest request, String updatedBy) {
        Map<String, Object> newOverrides = buildOverrideMap(request);
        StrategyParam saved = strategyParamService.upsertActiveOverrides(
                accountStrategyId, newOverrides, updatedBy);
        log.info("VCB params PUT (via unified): accountStrategyId={} overrides={} by={}",
                accountStrategyId, newOverrides.keySet(), updatedBy);
        return buildResponse(saved);
    }

    @Transactional
    public VcbParamResponse patchParams(UUID accountStrategyId, VcbParamUpdateRequest request, String updatedBy) {
        Map<String, Object> incoming = buildOverrideMap(request);
        Map<String, Object> existing = strategyParamService.findActive(accountStrategyId)
                .map(StrategyParam::getParamOverrides)
                .map(HashMap<String, Object>::new)
                .orElseGet(HashMap::new);
        existing.putAll(incoming);

        StrategyParam saved = strategyParamService.upsertActiveOverrides(
                accountStrategyId, existing, updatedBy);
        log.info("VCB params PATCH (via unified): accountStrategyId={} merged keys={} by={}",
                accountStrategyId, existing.keySet(), updatedBy);
        return buildResponse(saved);
    }

    @Transactional
    public void resetToDefaults(UUID accountStrategyId, String updatedBy) {
        strategyParamService.upsertActiveOverrides(accountStrategyId, Map.of(), updatedBy);
        log.info("VCB params reset to defaults (via unified): accountStrategyId={} by={}",
                accountStrategyId, updatedBy);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static VcbParamResponse buildResponse(StrategyParam entity) {
        Map<String, Object> overrides = entity.getParamOverrides() == null
                ? Map.of()
                : entity.getParamOverrides();
        return VcbParamResponse.builder()
                .accountStrategyId(entity.getAccountStrategyId())
                .hasCustomParams(!overrides.isEmpty())
                .overrides(overrides)
                .effectiveParams(VcbParams.merge(overrides))
                .version(entity.getVersion())
                .updatedAt(entity.getUpdatedTime())
                .build();
    }

    private Map<String, Object> buildOverrideMap(VcbParamUpdateRequest req) {
        Map<String, Object> m = new HashMap<>();
        if (req == null) return m;

        putIfPresent(m, "squeezeKcTolerance",   req.getSqueezeKcTolerance());
        putIfPresent(m, "atrRatioCompressMax",  req.getAtrRatioCompressMax());
        putIfPresent(m, "erCompressMax",        req.getErCompressMax());
        putIfPresent(m, "relVolBreakoutMin",    req.getRelVolBreakoutMin());
        putIfPresent(m, "relVolBreakoutMax",    req.getRelVolBreakoutMax());
        putIfPresent(m, "bodyRatioBreakoutMin", req.getBodyRatioBreakoutMin());
        putIfPresent(m, "biasErMin",            req.getBiasErMin());
        putIfPresent(m, "adxEntryMax",          req.getAdxEntryMax());
        putIfPresent(m, "longRsiMin",           req.getLongRsiMin());
        putIfPresent(m, "shortRsiMax",          req.getShortRsiMax());
        putIfPresent(m, "longDiSpreadMin",      req.getLongDiSpreadMin());
        putIfPresent(m, "shortDiSpreadMin",     req.getShortDiSpreadMin());
        putIfPresent(m, "stopAtrBuffer",        req.getStopAtrBuffer());
        putIfPresent(m, "tp1R",                 req.getTp1R());
        putIfPresent(m, "maxEntryRiskPct",      req.getMaxEntryRiskPct());
        putIfPresent(m, "runnerHalfR",          req.getRunnerHalfR());
        putIfPresent(m, "runnerBreakEvenR",     req.getRunnerBreakEvenR());
        putIfPresent(m, "runnerPhase2R",        req.getRunnerPhase2R());
        putIfPresent(m, "runnerPhase3R",        req.getRunnerPhase3R());
        putIfPresent(m, "runnerAtrPhase2",      req.getRunnerAtrPhase2());
        putIfPresent(m, "runnerAtrPhase3",      req.getRunnerAtrPhase3());
        putIfPresent(m, "runnerLockPhase2R",    req.getRunnerLockPhase2R());
        putIfPresent(m, "runnerLockPhase3R",    req.getRunnerLockPhase3R());
        putIfPresent(m, "minSignalScore",       req.getMinSignalScore());

        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }
}
