package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.request.VcbParamUpdateRequest;
import id.co.blackheart.dto.response.VcbParamResponse;
import id.co.blackheart.dto.vcb.VcbParams;
import id.co.blackheart.model.VcbStrategyParam;
import id.co.blackheart.repository.VcbStrategyParamRepository;
import id.co.blackheart.service.backtest.BacktestParamOverrideContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VcbStrategyParamService {

    private static final String CACHE_KEY_PREFIX = "vcb:params:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final VcbStrategyParamRepository paramRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    // ── Read ──────────────────────────────────────────────────────────────────

    public VcbParams getParams(UUID accountStrategyId) {
        // Backtest wizard overrides take precedence over stored overrides and
        // bypass the Redis cache entirely (per-run only; must not leak into
        // live execution). Layer order is (defaults < stored < wizard).
        Map<String, Object> wizardOverrides = BacktestParamOverrideContext.forStrategy("VCB");
        if (!wizardOverrides.isEmpty()) {
            return loadAndMergeWithWizardOverrides(accountStrategyId, wizardOverrides);
        }

        if (accountStrategyId == null) {
            return VcbParams.defaults();
        }

        String key = cacheKey(accountStrategyId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof VcbParams params) {
                log.debug("VCB params cache hit: accountStrategyId={}", accountStrategyId);
                return params;
            }
        } catch (Exception e) {
            log.warn("Redis read failed for VCB params key={}, falling through to DB: {}", key, e.getMessage());
        }

        VcbParams resolved = loadAndMerge(accountStrategyId);

        try {
            redisTemplate.opsForValue().set(key, resolved, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for VCB params key={}: {}", key, e.getMessage());
        }

        return resolved;
    }

    /** See {@code LsrStrategyParamService.loadAndMergeWithWizardOverrides}. */
    private VcbParams loadAndMergeWithWizardOverrides(UUID accountStrategyId,
                                                      Map<String, Object> wizardOverrides) {
        Map<String, Object> stored = accountStrategyId == null
                ? new HashMap<>()
                : paramRepository.findByAccountStrategyId(accountStrategyId)
                        .map(VcbStrategyParam::getParamOverrides)
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);
        stored.putAll(wizardOverrides);
        return VcbParams.merge(stored);
    }

    @Transactional(readOnly = true)
    public VcbParamResponse getParamResponse(UUID accountStrategyId) {
        return paramRepository.findByAccountStrategyId(accountStrategyId)
                .map(e -> VcbParamResponse.builder()
                        .accountStrategyId(accountStrategyId)
                        .hasCustomParams(!e.getParamOverrides().isEmpty())
                        .overrides(e.getParamOverrides())
                        .effectiveParams(VcbParams.merge(e.getParamOverrides()))
                        .version(e.getVersion())
                        .updatedAt(e.getUpdatedTime())
                        .build())
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
        try {
            VcbStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseGet(() -> VcbStrategyParam.builder()
                            .accountStrategyId(accountStrategyId)
                            .build());

            entity.setParamOverrides(newOverrides);
            entity.setUpdatedBy(updatedBy);
            if (entity.getCreatedBy() == null) entity.setCreatedBy(updatedBy);

            VcbStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);

            log.info("VCB params PUT: accountStrategyId={} overrides={} by={}", accountStrategyId, newOverrides.keySet(), updatedBy);
            return buildResponse(saved);
        } catch (DataIntegrityViolationException e) {
            VcbStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseThrow(() -> e);
            entity.setParamOverrides(newOverrides);
            entity.setUpdatedBy(updatedBy);
            VcbStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);
            return buildResponse(saved);
        }
    }

    @Transactional
    public VcbParamResponse patchParams(UUID accountStrategyId, VcbParamUpdateRequest request, String updatedBy) {
        Map<String, Object> incoming = buildOverrideMap(request);
        try {
            VcbStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseGet(() -> VcbStrategyParam.builder()
                            .accountStrategyId(accountStrategyId)
                            .paramOverrides(new HashMap<>())
                            .build());

            Map<String, Object> merged = new HashMap<>(entity.getParamOverrides());
            merged.putAll(incoming);

            entity.setParamOverrides(merged);
            entity.setUpdatedBy(updatedBy);
            if (entity.getCreatedBy() == null) entity.setCreatedBy(updatedBy);

            VcbStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);

            log.info("VCB params PATCH: accountStrategyId={} merged overrides={} by={}", accountStrategyId, merged.keySet(), updatedBy);
            return buildResponse(saved);
        } catch (DataIntegrityViolationException e) {
            VcbStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseThrow(() -> e);
            Map<String, Object> merged = new HashMap<>(entity.getParamOverrides());
            merged.putAll(incoming);
            entity.setParamOverrides(merged);
            entity.setUpdatedBy(updatedBy);
            VcbStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);
            return buildResponse(saved);
        }
    }

    @Transactional
    public void resetToDefaults(UUID accountStrategyId, String updatedBy) {
        int deleted = paramRepository.deleteByAccountStrategyId(accountStrategyId);
        if (deleted == 0) {
            throw new EntityNotFoundException("No custom VCB params found for accountStrategyId=" + accountStrategyId);
        }
        evictCacheAfterCommit(accountStrategyId);
        log.info("VCB params reset to defaults: accountStrategyId={} by={}", accountStrategyId, updatedBy);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private VcbParams loadAndMerge(UUID accountStrategyId) {
        return paramRepository.findByAccountStrategyId(accountStrategyId)
                .map(e -> VcbParams.merge(e.getParamOverrides()))
                .orElseGet(VcbParams::defaults);
    }

    private String cacheKey(UUID accountStrategyId) {
        return CACHE_KEY_PREFIX + accountStrategyId;
    }

    private void evictCache(UUID accountStrategyId) {
        try {
            redisTemplate.delete(cacheKey(accountStrategyId));
        } catch (Exception e) {
            log.warn("Failed to evict VCB params cache for accountStrategyId={}: {}", accountStrategyId, e.getMessage());
        }
    }

    private void evictCacheAfterCommit(UUID accountStrategyId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictCache(accountStrategyId);
            }
        });
    }

    private VcbParamResponse buildResponse(VcbStrategyParam entity) {
        return VcbParamResponse.builder()
                .accountStrategyId(entity.getAccountStrategyId())
                .hasCustomParams(!entity.getParamOverrides().isEmpty())
                .overrides(entity.getParamOverrides())
                .effectiveParams(VcbParams.merge(entity.getParamOverrides()))
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
