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

        if (req.getSqueezeKcTolerance() != null)   m.put("squeezeKcTolerance",   req.getSqueezeKcTolerance());
        if (req.getAtrRatioCompressMax() != null)   m.put("atrRatioCompressMax",  req.getAtrRatioCompressMax());
        if (req.getErCompressMax() != null)         m.put("erCompressMax",        req.getErCompressMax());
        if (req.getRelVolBreakoutMin() != null)     m.put("relVolBreakoutMin",    req.getRelVolBreakoutMin());
        if (req.getRelVolBreakoutMax() != null)     m.put("relVolBreakoutMax",    req.getRelVolBreakoutMax());
        if (req.getBodyRatioBreakoutMin() != null)  m.put("bodyRatioBreakoutMin", req.getBodyRatioBreakoutMin());
        if (req.getBiasErMin() != null)             m.put("biasErMin",            req.getBiasErMin());
        if (req.getAdxEntryMax() != null)           m.put("adxEntryMax",          req.getAdxEntryMax());
        if (req.getLongRsiMin() != null)            m.put("longRsiMin",           req.getLongRsiMin());
        if (req.getShortRsiMax() != null)           m.put("shortRsiMax",          req.getShortRsiMax());
        if (req.getLongDiSpreadMin() != null)       m.put("longDiSpreadMin",      req.getLongDiSpreadMin());
        if (req.getShortDiSpreadMin() != null)      m.put("shortDiSpreadMin",     req.getShortDiSpreadMin());
        if (req.getStopAtrBuffer() != null)         m.put("stopAtrBuffer",        req.getStopAtrBuffer());
        if (req.getTp1R() != null)                  m.put("tp1R",                 req.getTp1R());
        if (req.getMaxEntryRiskPct() != null)       m.put("maxEntryRiskPct",      req.getMaxEntryRiskPct());
        if (req.getRunnerHalfR() != null)           m.put("runnerHalfR",          req.getRunnerHalfR());
        if (req.getRunnerBreakEvenR() != null)      m.put("runnerBreakEvenR",     req.getRunnerBreakEvenR());
        if (req.getRunnerPhase2R() != null)         m.put("runnerPhase2R",        req.getRunnerPhase2R());
        if (req.getRunnerPhase3R() != null)         m.put("runnerPhase3R",        req.getRunnerPhase3R());
        if (req.getRunnerAtrPhase2() != null)       m.put("runnerAtrPhase2",      req.getRunnerAtrPhase2());
        if (req.getRunnerAtrPhase3() != null)       m.put("runnerAtrPhase3",      req.getRunnerAtrPhase3());
        if (req.getRunnerLockPhase2R() != null)     m.put("runnerLockPhase2R",    req.getRunnerLockPhase2R());
        if (req.getRunnerLockPhase3R() != null)     m.put("runnerLockPhase3R",    req.getRunnerLockPhase3R());
        if (req.getMinSignalScore() != null)        m.put("minSignalScore",       req.getMinSignalScore());

        return m;
    }
}
