package id.co.blackheart.service.strategy;

import id.co.blackheart.model.StrategyParam;
import id.co.blackheart.repository.StrategyParamRepository;
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
 * Unified parameter store for spec-driven strategies.
 *
 * <p>Operates on raw {@link Map}{@code <String, Object>} overrides — does not know
 * any strategy's parameter shape. Validation against archetype schema happens at
 * the controller layer (not here) so this service stays archetype-agnostic.
 *
 * <p>Architecture mirrors the legacy per-strategy param services
 * ({@code LsrStrategyParamService} et al.) so the operational behaviour is identical:
 * <ul>
 *   <li>Redis cache with 1-hour TTL, key {@code strategy:param:{accountStrategyId}}</li>
 *   <li>Cache-aside reads; cache-eviction-after-commit on writes</li>
 *   <li>Concurrent-insert handling via {@link DataIntegrityViolationException} retry</li>
 * </ul>
 *
 * <p>Legacy strategies (LSR, VCB, VBO) intentionally do NOT use this service —
 * they keep their own per-strategy services and tables. See
 * {@code docs/PARAMETRIC_ENGINE_BLUEPRINT.md} §4 (coexistence) for the rationale.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyParamService {

    static final String CACHE_KEY_PREFIX = "strategy:param:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final StrategyParamRepository repository;
    private final RedisTemplate<String, Object> redisTemplate;

    // ── Read ──────────────────────────────────────────────────────────────────────

    /**
     * Returns the raw override map for the account strategy. Empty map when no
     * row exists. Callers merge with archetype defaults themselves.
     *
     * <p>Cache-first; falls back to DB. Best-effort cache writes — strategy
     * execution never fails because of a Redis hiccup.
     */
    public Map<String, Object> getOverrides(UUID accountStrategyId) {
        if (accountStrategyId == null) {
            return new HashMap<>();
        }

        String key = cacheKey(accountStrategyId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) cached;
                return new HashMap<>(map);
            }
        } catch (Exception e) {
            log.warn("Redis read failed for strategy_param key={}, falling through to DB: {}",
                    key, e.getMessage());
        }

        Map<String, Object> resolved = repository.findByAccountStrategyId(accountStrategyId)
                .map(StrategyParam::getParamOverrides)
                .map(HashMap::new)
                .map(m -> (Map<String, Object>) m)
                .orElseGet(HashMap::new);

        try {
            redisTemplate.opsForValue().set(key, resolved, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for strategy_param key={}: {}", key, e.getMessage());
        }

        return resolved;
    }

    /**
     * Loads the entity for the API layer (which needs version + audit timestamps).
     * Read-only transaction; no caching here — controllers serve infrequent UI traffic.
     */
    @Transactional(readOnly = true)
    public Optional<StrategyParam> findEntity(UUID accountStrategyId) {
        if (accountStrategyId == null) {
            return Optional.empty();
        }
        return repository.findByAccountStrategyId(accountStrategyId);
    }

    // ── Write ─────────────────────────────────────────────────────────────────────

    /**
     * Replace all overrides for the account strategy. Empty map collapses the row
     * to "no overrides" (the row stays for audit timestamps but param_overrides becomes {}).
     *
     * <p>Validation must have happened upstream — the service trusts the caller
     * to pass a map matching the archetype schema.
     */
    @Transactional
    public StrategyParam putOverrides(UUID accountStrategyId,
                                      Map<String, Object> newOverrides,
                                      String updatedBy) {
        Map<String, Object> overrides = newOverrides == null ? new HashMap<>() : new HashMap<>(newOverrides);
        try {
            return upsertOverrides(accountStrategyId, overrides, updatedBy, /*merge=*/false);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert: another thread created the row — load and retry as plain UPDATE.
            return upsertOverrides(accountStrategyId, overrides, updatedBy, /*merge=*/false);
        }
    }

    /**
     * Merge incoming entries into existing overrides. Existing keys not in the
     * incoming map are preserved.
     */
    @Transactional
    public StrategyParam patchOverrides(UUID accountStrategyId,
                                        Map<String, Object> incoming,
                                        String updatedBy) {
        Map<String, Object> partial = incoming == null ? new HashMap<>() : new HashMap<>(incoming);
        try {
            return upsertOverrides(accountStrategyId, partial, updatedBy, /*merge=*/true);
        } catch (DataIntegrityViolationException e) {
            return upsertOverrides(accountStrategyId, partial, updatedBy, /*merge=*/true);
        }
    }

    /**
     * Drop the override row entirely. Effective params revert to archetype defaults
     * on next read.
     *
     * @throws EntityNotFoundException if no row exists for the account strategy
     */
    @Transactional
    public void deleteOverrides(UUID accountStrategyId, String updatedBy) {
        int deleted = repository.deleteByAccountStrategyId(accountStrategyId);
        if (deleted == 0) {
            throw new EntityNotFoundException(
                    "No strategy_param row for accountStrategyId=" + accountStrategyId);
        }
        evictCacheAfterCommit(accountStrategyId);
        log.info("strategy_param deleted: accountStrategyId={} by={}", accountStrategyId, updatedBy);
    }

    // ── Internals ─────────────────────────────────────────────────────────────────

    private StrategyParam upsertOverrides(UUID accountStrategyId,
                                          Map<String, Object> incoming,
                                          String updatedBy,
                                          boolean merge) {
        StrategyParam entity = repository.findByAccountStrategyId(accountStrategyId)
                .orElseGet(() -> StrategyParam.builder()
                        .accountStrategyId(accountStrategyId)
                        .paramOverrides(new HashMap<>())
                        .build());

        Map<String, Object> resolved;
        if (merge) {
            resolved = new HashMap<>(entity.getParamOverrides());
            resolved.putAll(incoming);
        } else {
            resolved = incoming;
        }

        entity.setParamOverrides(resolved);
        entity.setUpdatedBy(updatedBy);
        if (entity.getCreatedBy() == null) {
            entity.setCreatedBy(updatedBy);
        }

        StrategyParam saved = repository.save(entity);
        evictCacheAfterCommit(accountStrategyId);

        log.info("strategy_param {}: accountStrategyId={} keys={} by={}",
                merge ? "PATCH" : "PUT",
                accountStrategyId,
                resolved.keySet(),
                updatedBy);

        return saved;
    }

    static String cacheKey(UUID accountStrategyId) {
        return CACHE_KEY_PREFIX + accountStrategyId;
    }

    private void evictCache(UUID accountStrategyId) {
        try {
            redisTemplate.delete(cacheKey(accountStrategyId));
        } catch (Exception e) {
            log.warn("Failed to evict strategy_param cache for accountStrategyId={}: {}",
                    accountStrategyId, e.getMessage());
        }
    }

    /**
     * Eviction must wait for transaction commit — otherwise a concurrent reader
     * could repopulate the cache from pre-commit DB state, giving a stale view
     * after the writer's transaction lands.
     */
    private void evictCacheAfterCommit(UUID accountStrategyId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictCache(accountStrategyId);
                }
            });
        } else {
            // Defensive fallback for callers without a transaction (tests, edge cases)
            evictCache(accountStrategyId);
        }
    }
}
