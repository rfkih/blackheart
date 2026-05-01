package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.lsr.LsrParams;
import id.co.blackheart.dto.request.LsrParamUpdateRequest;
import id.co.blackheart.dto.response.LsrParamResponse;
import id.co.blackheart.model.LsrStrategyParam;
import id.co.blackheart.repository.LsrStrategyParamRepository;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Manages per-account-strategy LSR parameter overrides.
 *
 * <p>Architecture:
 * <ol>
 *   <li>Resolved {@link LsrParams} objects are cached in Redis with a 1-hour TTL
 *       under the key {@code lsr:params:{accountStrategyId}}.</li>
 *   <li>DB rows only exist for account strategies that have at least one override —
 *       a missing row means "use all defaults".</li>
 *   <li>PUT replaces the entire override map; PATCH merges into the existing one.</li>
 *   <li>DELETE removes the DB row and cache entry, reverting to defaults.</li>
 * </ol>
 *
 * <p>Passing {@code null} as {@code accountStrategyId} (e.g. in backtest or test contexts)
 * always returns defaults without hitting cache or DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LsrStrategyParamService {

    private static final String CACHE_KEY_PREFIX = "lsr:params:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final LsrStrategyParamRepository paramRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    // ── Read ──────────────────────────────────────────────────────────────────────

    /**
     * Returns the resolved (defaults + overrides merged) {@link LsrParams} for the given
     * account strategy. Cache-first; falls back to DB then falls back to defaults.
     *
     * <p>This is on the hot path of strategy execution — Redis should serve the vast majority
     * of calls with sub-millisecond latency.
     */
    public LsrParams getParams(UUID accountStrategyId) {
        // Backtest wizard overrides: when a run is active and supplied its own
        // tuning, skip the shared Redis cache entirely — those overrides are
        // per-run and must not leak into live execution. Layer order is
        // (defaults < stored overrides < wizard overrides).
        Map<String, Object> wizardOverrides = BacktestParamOverrideContext.forStrategy("LSR");
        if (!wizardOverrides.isEmpty()) {
            return loadAndMergeWithWizardOverrides(accountStrategyId, wizardOverrides);
        }

        if (accountStrategyId == null) {
            return LsrParams.defaults();
        }

        // 1. Cache lookup
        String key = cacheKey(accountStrategyId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof LsrParams params) {
                log.debug("LSR params cache hit: accountStrategyId={}", accountStrategyId);
                return params;
            }
        } catch (Exception e) {
            log.warn("Redis read failed for LSR params key={}, falling through to DB: {}", key, e.getMessage());
        }

        // 2. DB lookup + merge
        LsrParams resolved = loadAndMerge(accountStrategyId);

        // 3. Populate cache (best-effort — don't fail strategy execution on cache write failure)
        try {
            redisTemplate.opsForValue().set(key, resolved, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for LSR params key={}: {}", key, e.getMessage());
        }

        return resolved;
    }

    /**
     * Backtest-only resolution: loads the stored override map (if any), overlays
     * the wizard's per-run overrides on top, and merges the whole thing onto
     * defaults in one shot. Never touches the Redis cache — results are
     * per-run and caching them would poison live execution on subsequent
     * reads.
     */
    private LsrParams loadAndMergeWithWizardOverrides(UUID accountStrategyId,
                                                      Map<String, Object> wizardOverrides) {
        Map<String, Object> stored = accountStrategyId == null
                ? new HashMap<>()
                : paramRepository.findByAccountStrategyId(accountStrategyId)
                        .map(LsrStrategyParam::getParamOverrides)
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);
        stored.putAll(wizardOverrides);   // wizard wins on key collisions
        return LsrParams.merge(stored);
    }

    /**
     * Returns the full response DTO for the REST API (includes override map, version, etc.).
     */
    @Transactional(readOnly = true)
    public LsrParamResponse getParamResponse(UUID accountStrategyId) {
        Optional<LsrStrategyParam> entity = paramRepository.findByAccountStrategyId(accountStrategyId);

        return entity.map(e -> LsrParamResponse.builder()
                        .accountStrategyId(accountStrategyId)
                        .hasCustomParams(!e.getParamOverrides().isEmpty())
                        .overrides(e.getParamOverrides())
                        .effectiveParams(LsrParams.merge(e.getParamOverrides()))
                        .version(e.getVersion())
                        .updatedAt(e.getUpdatedTime())
                        .build())
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

    /**
     * Replaces the entire override map for the given account strategy.
     * Null fields in the request are excluded from the override map (treated as "use default").
     * Evicts the cache on success.
     */
    @Transactional
    public LsrParamResponse putParams(UUID accountStrategyId, LsrParamUpdateRequest request, String updatedBy) {
        Map<String, Object> newOverrides = buildOverrideMap(request);
        try {
            LsrStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseGet(() -> LsrStrategyParam.builder()
                            .accountStrategyId(accountStrategyId)
                            .build());

            entity.setParamOverrides(newOverrides);
            entity.setUpdatedBy(updatedBy);
            if (entity.getCreatedBy() == null) entity.setCreatedBy(updatedBy);

            LsrStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);

            log.info("LSR params PUT: accountStrategyId={} overrides={} by={}", accountStrategyId, newOverrides.keySet(), updatedBy);

            return buildResponse(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert: another thread created the row — load it and update
            LsrStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseThrow(() -> e);
            entity.setParamOverrides(newOverrides);
            entity.setUpdatedBy(updatedBy);
            LsrStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);
            return buildResponse(saved);
        }
    }

    /**
     * Merges non-null fields from the request into the existing override map.
     * Existing overrides not mentioned in the request are preserved.
     * Evicts the cache on success.
     */
    @Transactional
    public LsrParamResponse patchParams(UUID accountStrategyId, LsrParamUpdateRequest request, String updatedBy) {
        Map<String, Object> incoming = buildOverrideMap(request);
        try {
            LsrStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseGet(() -> LsrStrategyParam.builder()
                            .accountStrategyId(accountStrategyId)
                            .paramOverrides(new HashMap<>())
                            .build());

            Map<String, Object> merged = new HashMap<>(entity.getParamOverrides());
            merged.putAll(incoming);

            entity.setParamOverrides(merged);
            entity.setUpdatedBy(updatedBy);
            if (entity.getCreatedBy() == null) entity.setCreatedBy(updatedBy);

            LsrStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);

            log.info("LSR params PATCH: accountStrategyId={} merged overrides={} by={}", accountStrategyId, merged.keySet(), updatedBy);

            return buildResponse(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert: another thread created the row — load it and merge
            LsrStrategyParam entity = paramRepository.findByAccountStrategyId(accountStrategyId)
                    .orElseThrow(() -> e);
            Map<String, Object> merged = new HashMap<>(entity.getParamOverrides());
            merged.putAll(incoming);
            entity.setParamOverrides(merged);
            entity.setUpdatedBy(updatedBy);
            LsrStrategyParam saved = paramRepository.save(entity);
            evictCacheAfterCommit(accountStrategyId);
            return buildResponse(saved);
        }
    }

    /**
     * Deletes all custom overrides for the given account strategy, reverting it to defaults.
     * Throws {@link EntityNotFoundException} if no custom params exist.
     */
    @Transactional
    public void resetToDefaults(UUID accountStrategyId, String updatedBy) {
        int deleted = paramRepository.deleteByAccountStrategyId(accountStrategyId);
        if (deleted == 0) {
            throw new EntityNotFoundException(
                    "No custom LSR params found for accountStrategyId=" + accountStrategyId);
        }
        evictCache(accountStrategyId);
        log.info("LSR params reset to defaults: accountStrategyId={} by={}", accountStrategyId, updatedBy);
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private LsrParams loadAndMerge(UUID accountStrategyId) {
        return paramRepository.findByAccountStrategyId(accountStrategyId)
                .map(e -> LsrParams.merge(e.getParamOverrides()))
                .orElseGet(LsrParams::defaults);
    }

    private String cacheKey(UUID accountStrategyId) {
        return CACHE_KEY_PREFIX + accountStrategyId;
    }

    private void evictCache(UUID accountStrategyId) {
        try {
            redisTemplate.delete(cacheKey(accountStrategyId));
        } catch (Exception e) {
            log.warn("Failed to evict LSR params cache for accountStrategyId={}: {}", accountStrategyId, e.getMessage());
        }
    }

    /**
     * Registers a post-commit hook to evict the cache only after the surrounding transaction
     * has been committed. Prevents a concurrent reader from re-populating the cache with
     * stale DB data during the window between eviction and commit.
     */
    private void evictCacheAfterCommit(UUID accountStrategyId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictCache(accountStrategyId);
            }
        });
    }

    private LsrParamResponse buildResponse(LsrStrategyParam entity) {
        return LsrParamResponse.builder()
                .accountStrategyId(entity.getAccountStrategyId())
                .hasCustomParams(!entity.getParamOverrides().isEmpty())
                .overrides(entity.getParamOverrides())
                .effectiveParams(LsrParams.merge(entity.getParamOverrides()))
                .version(entity.getVersion())
                .updatedAt(entity.getUpdatedTime())
                .build();
    }

    /**
     * Converts a {@link LsrParamUpdateRequest} to a flat Map, including only non-null fields.
     * Numeric values are stored as {@link java.math.BigDecimal} or {@link Integer} to preserve precision.
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
