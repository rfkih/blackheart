package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.request.VboParamUpdateRequest;
import id.co.blackheart.dto.response.VboParamResponse;
import id.co.blackheart.dto.vbo.VboParams;
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
 * VBO parameter shim over {@link StrategyParamService} (V29+ unified table).
 * See {@link LsrStrategyParamService} for the architecture rationale.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VboStrategyParamService {

    private static final String STRATEGY_CODE = "VBO";

    private final StrategyParamService strategyParamService;

    // ── Read ──────────────────────────────────────────────────────────────────

    public VboParams getParams(UUID accountStrategyId) {
        Map<String, Object> wizardOverrides = BacktestParamOverrideContext.forStrategy(STRATEGY_CODE);

        if (accountStrategyId == null && wizardOverrides.isEmpty()) {
            return VboParams.defaults();
        }

        Map<String, Object> stored = accountStrategyId == null
                ? new HashMap<>()
                : strategyParamService.resolveOverridesForStrategy(STRATEGY_CODE, accountStrategyId);

        if (wizardOverrides.isEmpty()) {
            return VboParams.merge(stored);
        }
        Map<String, Object> layered = new HashMap<>(stored);
        layered.putAll(wizardOverrides);
        return VboParams.merge(layered);
    }

    @Transactional(readOnly = true)
    public VboParamResponse getParamResponse(UUID accountStrategyId) {
        Optional<StrategyParam> entity = strategyParamService.findActive(accountStrategyId);
        return entity.map(VboStrategyParamService::buildResponse)
                .orElseGet(() -> VboParamResponse.builder()
                        .accountStrategyId(accountStrategyId)
                        .hasCustomParams(false)
                        .overrides(Map.of())
                        .effectiveParams(VboParams.defaults())
                        .version(null)
                        .updatedAt(null)
                        .build());
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Transactional
    public VboParamResponse putParams(UUID accountStrategyId, VboParamUpdateRequest request, String updatedBy) {
        Map<String, Object> newOverrides = buildOverrideMap(request);
        StrategyParam saved = strategyParamService.upsertActiveOverrides(
                accountStrategyId, newOverrides, updatedBy);
        log.info("VBO params PUT (via unified): accountStrategyId={} overrides={} by={}",
                accountStrategyId, newOverrides.keySet(), updatedBy);
        return buildResponse(saved);
    }

    @Transactional
    public VboParamResponse patchParams(UUID accountStrategyId, VboParamUpdateRequest request, String updatedBy) {
        Map<String, Object> incoming = buildOverrideMap(request);
        Map<String, Object> existing = strategyParamService.findActive(accountStrategyId)
                .map(StrategyParam::getParamOverrides)
                .map(HashMap<String, Object>::new)
                .orElseGet(HashMap::new);
        existing.putAll(incoming);

        StrategyParam saved = strategyParamService.upsertActiveOverrides(
                accountStrategyId, existing, updatedBy);
        log.info("VBO params PATCH (via unified): accountStrategyId={} merged keys={} by={}",
                accountStrategyId, existing.keySet(), updatedBy);
        return buildResponse(saved);
    }

    @Transactional
    public void resetToDefaults(UUID accountStrategyId, String updatedBy) {
        strategyParamService.upsertActiveOverrides(accountStrategyId, Map.of(), updatedBy);
        log.info("VBO params reset to defaults (via unified): accountStrategyId={} by={}",
                accountStrategyId, updatedBy);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static VboParamResponse buildResponse(StrategyParam entity) {
        Map<String, Object> overrides = entity.getParamOverrides() == null
                ? Map.of()
                : entity.getParamOverrides();
        return VboParamResponse.builder()
                .accountStrategyId(entity.getAccountStrategyId())
                .hasCustomParams(!overrides.isEmpty())
                .overrides(overrides)
                .effectiveParams(VboParams.merge(overrides))
                .version(entity.getVersion())
                .updatedAt(entity.getUpdatedTime())
                .build();
    }

    private Map<String, Object> buildOverrideMap(VboParamUpdateRequest req) {
        Map<String, Object> m = new HashMap<>();
        if (req == null) return m;

        // Compression
        if (req.getCompressionBbWidthPctMax() != null) m.put("compressionBbWidthPctMax", req.getCompressionBbWidthPctMax());
        if (req.getCompressionAdxMax() != null)        m.put("compressionAdxMax",        req.getCompressionAdxMax());
        if (req.getRequireKcSqueeze() != null)         m.put("requireKcSqueeze",         req.getRequireKcSqueeze());

        // Entry-bar ADX band
        if (req.getAdxEntryMin() != null)              m.put("adxEntryMin",              req.getAdxEntryMin());
        if (req.getAdxEntryMax() != null)              m.put("adxEntryMax",              req.getAdxEntryMax());

        // Breakout confirmation
        if (req.getRequireDonchianBreak() != null)     m.put("requireDonchianBreak",     req.getRequireDonchianBreak());
        if (req.getRequireTrendAlignment() != null)    m.put("requireTrendAlignment",    req.getRequireTrendAlignment());
        if (req.getEma50SlopeMin() != null)            m.put("ema50SlopeMin",            req.getEma50SlopeMin());
        if (req.getAtrExpansionMin() != null)          m.put("atrExpansionMin",          req.getAtrExpansionMin());
        if (req.getRvolMin() != null)                  m.put("rvolMin",                  req.getRvolMin());

        // Candle quality
        if (req.getBodyRatioMin() != null)             m.put("bodyRatioMin",             req.getBodyRatioMin());
        if (req.getClvMin() != null)                   m.put("clvMin",                   req.getClvMin());
        if (req.getClvMax() != null)                   m.put("clvMax",                   req.getClvMax());

        // RSI sanity
        if (req.getLongRsiMax() != null)               m.put("longRsiMax",               req.getLongRsiMax());
        if (req.getShortRsiMin() != null)              m.put("shortRsiMin",              req.getShortRsiMin());

        // Risk / exits
        if (req.getStopAtrBuffer() != null)            m.put("stopAtrBuffer",            req.getStopAtrBuffer());
        if (req.getMaxEntryRiskPct() != null)          m.put("maxEntryRiskPct",          req.getMaxEntryRiskPct());
        if (req.getTp1R() != null)                     m.put("tp1R",                     req.getTp1R());

        // Management
        if (req.getBreakEvenR() != null)               m.put("breakEvenR",               req.getBreakEvenR());
        if (req.getRunnerBreakEvenR() != null)         m.put("runnerBreakEvenR",         req.getRunnerBreakEvenR());
        if (req.getRunnerPhase2R() != null)            m.put("runnerPhase2R",            req.getRunnerPhase2R());
        if (req.getRunnerPhase3R() != null)            m.put("runnerPhase3R",            req.getRunnerPhase3R());
        if (req.getRunnerAtrPhase2() != null)          m.put("runnerAtrPhase2",          req.getRunnerAtrPhase2());
        if (req.getRunnerAtrPhase3() != null)          m.put("runnerAtrPhase3",          req.getRunnerAtrPhase3());
        if (req.getRunnerLockPhase2R() != null)        m.put("runnerLockPhase2R",        req.getRunnerLockPhase2R());
        if (req.getRunnerLockPhase3R() != null)        m.put("runnerLockPhase3R",        req.getRunnerLockPhase3R());

        // Score
        if (req.getMinSignalScore() != null)           m.put("minSignalScore",           req.getMinSignalScore());

        return m;
    }
}
