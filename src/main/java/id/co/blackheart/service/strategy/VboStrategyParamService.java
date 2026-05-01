package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.request.VboParamUpdateRequest;
import id.co.blackheart.dto.response.VboParamResponse;
import id.co.blackheart.dto.vbo.VboParams;
import id.co.blackheart.model.VboStrategyParam;
import id.co.blackheart.repository.VboStrategyParamRepository;
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
public class VboStrategyParamService {

    private static final String CACHE_KEY_PREFIX = "vbo:params:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final VboStrategyParamRepository paramRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    // ── Read ──────────────────────────────────────────────────────────────────

    public VboParams getParams(UUID accountStrategyId) {
        // Backtest wizard overrides take precedence over stored overrides and
        // bypass the Redis cache entirely (per-run only; must not leak into
        // live execution). Layer order is (defaults < stored < wizard).
        Map<String, Object> wizardOverrides = BacktestParamOverrideContext.forStrategy("VBO");
        if (!wizardOverrides.isEmpty()) {
            return loadAndMergeWithWizardOverrides(accountStrategyId, wizardOverrides);
        }

        if (accountStrategyId == null) {
            return VboParams.defaults();
        }

        String key = cacheKey(accountStrategyId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof VboParams params) {
                log.debug("VBO params cache hit: accountStrategyId={}", accountStrategyId);
                return params;
            }
        } catch (Exception e) {
            log.warn("Redis read failed for VBO params key={}, falling through to DB: {}", key, e.getMessage());
        }

        VboParams resolved = loadAndMerge(accountStrategyId);

        try {
            redisTemplate.opsForValue().set(key, resolved, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for VBO params key={}: {}", key, e.getMessage());
        }

        return resolved;
    }

    /** See {@code LsrStrategyParamService.loadAndMergeWithWizardOverrides}. */
    private VboParams loadAndMergeWithWizardOverrides(UUID accountStrategyId,
                                                      Map<String, Object> wizardOverrides) {
        Map<String, Object> stored = accountStrategyId == null
                ? new HashMap<>()
                : paramRepository.findByAccountStrategyId(accountStrategyId)
                        .map(VboStrategyParam::getParamOverrides)
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);
        stored.putAll(wizardOverrides);
        return VboParams.merge(stored);
    }

    @Transactional(readOnly = true)
    public VboParamResponse getParamResponse(UUID accountStrategyId) {
        return paramRepository.findByAccountStrategyId(accountStrategyId)
                .map(e -> VboParamResponse.builder()
                        .accountStrategyId(accountStrategyId)
                        .hasCustomParams(!e.getParamOverrides().isEmpty())
                        .overrides(e.getParamOverrides())
                        .effectiveParams(VboParams.merge(e.getParamOverrides()))
                        .version(e.getVersion())
                        .updatedAt(e.getUpdatedTime())
                        .build())
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
        try {
            VboStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseGet(() -> VboStrategyParam.builder()
                            .accountStrategyId(accountStrategyId)
                            .build());

            entity.setParamOverrides(newOverrides);
            entity.setUpdatedBy(updatedBy);
            if (entity.getCreatedBy() == null) entity.setCreatedBy(updatedBy);

            VboStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);

            log.info("VBO params PUT: accountStrategyId={} overrides={} by={}", accountStrategyId, newOverrides.keySet(), updatedBy);
            return buildResponse(saved);
        } catch (DataIntegrityViolationException e) {
            VboStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseThrow(() -> e);
            entity.setParamOverrides(newOverrides);
            entity.setUpdatedBy(updatedBy);
            VboStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);
            return buildResponse(saved);
        }
    }

    @Transactional
    public VboParamResponse patchParams(UUID accountStrategyId, VboParamUpdateRequest request, String updatedBy) {
        Map<String, Object> incoming = buildOverrideMap(request);
        try {
            VboStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseGet(() -> VboStrategyParam.builder()
                            .accountStrategyId(accountStrategyId)
                            .paramOverrides(new HashMap<>())
                            .build());

            Map<String, Object> merged = new HashMap<>(entity.getParamOverrides());
            merged.putAll(incoming);

            entity.setParamOverrides(merged);
            entity.setUpdatedBy(updatedBy);
            if (entity.getCreatedBy() == null) entity.setCreatedBy(updatedBy);

            VboStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);

            log.info("VBO params PATCH: accountStrategyId={} merged overrides={} by={}", accountStrategyId, merged.keySet(), updatedBy);
            return buildResponse(saved);
        } catch (DataIntegrityViolationException e) {
            VboStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseThrow(() -> e);
            Map<String, Object> merged = new HashMap<>(entity.getParamOverrides());
            merged.putAll(incoming);
            entity.setParamOverrides(merged);
            entity.setUpdatedBy(updatedBy);
            VboStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);
            return buildResponse(saved);
        }
    }

    @Transactional
    public void resetToDefaults(UUID accountStrategyId, String updatedBy) {
        int deleted = paramRepository.deleteByAccountStrategyId(accountStrategyId);
        if (deleted == 0) {
            throw new EntityNotFoundException("No custom VBO params found for accountStrategyId=" + accountStrategyId);
        }
        evictCacheAfterCommit(accountStrategyId);
        log.info("VBO params reset to defaults: accountStrategyId={} by={}", accountStrategyId, updatedBy);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private VboParams loadAndMerge(UUID accountStrategyId) {
        return paramRepository.findByAccountStrategyId(accountStrategyId)
                .map(e -> VboParams.merge(e.getParamOverrides()))
                .orElseGet(VboParams::defaults);
    }

    private String cacheKey(UUID accountStrategyId) {
        return CACHE_KEY_PREFIX + accountStrategyId;
    }

    private void evictCache(UUID accountStrategyId) {
        try {
            redisTemplate.delete(cacheKey(accountStrategyId));
        } catch (Exception e) {
            log.warn("Failed to evict VBO params cache for accountStrategyId={}: {}", accountStrategyId, e.getMessage());
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

    private VboParamResponse buildResponse(VboStrategyParam entity) {
        return VboParamResponse.builder()
                .accountStrategyId(entity.getAccountStrategyId())
                .hasCustomParams(!entity.getParamOverrides().isEmpty())
                .overrides(entity.getParamOverrides())
                .effectiveParams(VboParams.merge(entity.getParamOverrides()))
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
