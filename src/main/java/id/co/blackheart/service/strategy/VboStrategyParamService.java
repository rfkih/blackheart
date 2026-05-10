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
        putIfPresent(m, "compressionBbWidthPctMax", req.getCompressionBbWidthPctMax());
        putIfPresent(m, "compressionAdxMax",        req.getCompressionAdxMax());
        putIfPresent(m, "requireKcSqueeze",         req.getRequireKcSqueeze());

        // Entry-bar ADX band
        putIfPresent(m, "adxEntryMin",              req.getAdxEntryMin());
        putIfPresent(m, "adxEntryMax",              req.getAdxEntryMax());

        // Breakout confirmation
        putIfPresent(m, "requireDonchianBreak",     req.getRequireDonchianBreak());
        putIfPresent(m, "requireTrendAlignment",    req.getRequireTrendAlignment());
        putIfPresent(m, "ema50SlopeMin",            req.getEma50SlopeMin());
        putIfPresent(m, "atrExpansionMin",          req.getAtrExpansionMin());
        putIfPresent(m, "rvolMin",                  req.getRvolMin());

        // Candle quality
        putIfPresent(m, "bodyRatioMin",             req.getBodyRatioMin());
        putIfPresent(m, "clvMin",                   req.getClvMin());
        putIfPresent(m, "clvMax",                   req.getClvMax());

        // RSI sanity
        putIfPresent(m, "longRsiMax",               req.getLongRsiMax());
        putIfPresent(m, "shortRsiMin",              req.getShortRsiMin());

        // Risk / exits
        putIfPresent(m, "stopAtrBuffer",            req.getStopAtrBuffer());
        putIfPresent(m, "maxEntryRiskPct",          req.getMaxEntryRiskPct());
        putIfPresent(m, "tp1R",                     req.getTp1R());

        // Management
        putIfPresent(m, "breakEvenR",               req.getBreakEvenR());
        putIfPresent(m, "runnerBreakEvenR",         req.getRunnerBreakEvenR());
        putIfPresent(m, "runnerPhase2R",            req.getRunnerPhase2R());
        putIfPresent(m, "runnerPhase3R",            req.getRunnerPhase3R());
        putIfPresent(m, "runnerAtrPhase2",          req.getRunnerAtrPhase2());
        putIfPresent(m, "runnerAtrPhase3",          req.getRunnerAtrPhase3());
        putIfPresent(m, "runnerLockPhase2R",        req.getRunnerLockPhase2R());
        putIfPresent(m, "runnerLockPhase3R",        req.getRunnerLockPhase3R());

        // Score
        putIfPresent(m, "minSignalScore",           req.getMinSignalScore());

        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }
}
