package id.co.blackheart.service.strategy;

import id.co.blackheart.exception.StrategyHasOpenTradesException;
import id.co.blackheart.model.StrategyParam;
import id.co.blackheart.repository.StrategyParamRepository;
import id.co.blackheart.repository.TradesRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified saved-preset store for every strategy (V29+).
 *
 * <p>Operates on raw {@link Map}{@code <String, Object>} overrides — does not
 * know any strategy's parameter shape. Per-strategy default merging happens at
 * the executor / strategy-service layer (e.g. {@code LsrParams.merge(map)}),
 * not here.
 *
 * <p>Contract:
 * <ul>
 *   <li>Each {@code account_strategy_id} has 0..N saved presets.</li>
 *   <li>At most one preset per account_strategy is {@code active} at a time —
 *       enforced both by the partial unique index in V29 and by
 *       {@link #activate(UUID, String)} which clears siblings inside one TX.</li>
 *   <li>Soft-deleted presets stay resolvable by {@code paramId} for historical
 *       backtests; they are hidden from list APIs.</li>
 *   <li>The active preset's overrides are Redis-cached under
 *       {@code strategy:param:active:{accountStrategyId}} (1h TTL) for hot-path
 *       reads on the live trading path.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyParamService {

    static final String ACTIVE_CACHE_KEY_PREFIX = "strategy:param:active:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final String NO_PARAM_MSG_PREFIX = "No strategy_param with paramId=";

    private final StrategyParamRepository repository;
    private final TradesRepository tradesRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Self-reference used to route internal @Transactional calls through the
     * Spring proxy. {@link #upsertActiveOverrides} delegates to
     * {@link #create} (also @Transactional) — a direct {@code this} call
     * bypasses the proxy and silences the inner method's transactional advice.
     * Defaults to {@code this} so unit tests work without a Spring context.
     */
    private StrategyParamService self = this;

    @Autowired
    public void setSelf(@Lazy StrategyParamService self) {
        this.self = self;
    }

    // ── Hot-path read (live executor) ────────────────────────────────────────────

    /**
     * Returns the active preset's override map for the account_strategy.
     * Empty map when no active preset exists (caller should fall back to
     * archetype defaults). Cache-first, DB fallback. Best-effort caching —
     * strategy execution never fails on a Redis hiccup.
     */
    public Map<String, Object> getActiveOverrides(UUID accountStrategyId) {
        if (accountStrategyId == null) {
            return new HashMap<>();
        }

        String key = activeCacheKey(accountStrategyId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) cached;
                return new HashMap<>(map);
            }
        } catch (Exception | Error e) {
            log.warn("Redis read failed for active strategy_param key={}, falling through to DB: {}",
                    key, e.getMessage());
        }

        Map<String, Object> resolved = repository.findActiveByAccountStrategyId(accountStrategyId)
                .map(StrategyParam::getParamOverrides)
                .map(HashMap<String, Object>::new)
                .orElseGet(HashMap::new);

        try {
            redisTemplate.opsForValue().set(key, resolved, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for active strategy_param key={}: {}", key, e.getMessage());
        }
        return resolved;
    }

    /**
     * Strategy-aware resolve: honours a backtest preset pin (if set on this
     * thread) before falling back to the active preset for the
     * account_strategy. Use this from strategy-param services and the spec
     * adapter so backtests can lock onto a specific (possibly soft-deleted)
     * preset row while live execution continues to read the active one.
     */
    public Map<String, Object> resolveOverridesForStrategy(String strategyCode, UUID accountStrategyId) {
        UUID pinnedParamId = BacktestParamPresetContext.forStrategy(strategyCode);
        if (pinnedParamId != null) {
            Map<String, Object> pinned = getOverridesByParamId(pinnedParamId);
            if (pinned.isEmpty()) {
                log.warn("Pinned strategy_param paramId={} for strategyCode={} resolved to empty overrides — preset may be missing or hard-deleted; falling back to defaults",
                        pinnedParamId, strategyCode);
            }
            return pinned;
        }
        return getActiveOverrides(accountStrategyId);
    }

    /**
     * Backtest-path read: resolve any preset by {@code paramId}, including
     * soft-deleted ones. Returns empty map if the preset doesn't exist —
     * caller decides whether to throw or fall back to defaults.
     */
    public Map<String, Object> getOverridesByParamId(UUID paramId) {
        if (paramId == null) return new HashMap<>();
        return repository.findById(paramId)
                .map(StrategyParam::getParamOverrides)
                .map(HashMap<String, Object>::new)
                .orElseGet(HashMap::new);
    }

    /**
     * Legacy-shim helper: replace the active preset's override map for an
     * account_strategy. If no active preset exists, creates one named
     * {@code "default"} and activates it. Used by the legacy
     * {@code /api/v1/{lsr,vcb,vbo}-params/{id}} controllers so the existing
     * frontend forms keep working through one source of truth.
     */
    @Transactional
    public StrategyParam upsertActiveOverrides(UUID accountStrategyId,
                                               Map<String, Object> overrides,
                                               String updatedBy) {
        if (accountStrategyId == null) {
            throw new IllegalArgumentException("accountStrategyId is required");
        }
        Map<String, Object> resolved = overrides == null ? new HashMap<>() : new HashMap<>(overrides);

        Optional<StrategyParam> active = repository.findActiveByAccountStrategyId(accountStrategyId);
        if (active.isPresent()) {
            StrategyParam entity = active.get();
            entity.setParamOverrides(resolved);
            entity.setUpdatedBy(updatedBy);
            StrategyParam saved = repository.save(entity);
            evictActiveCacheAfterCommit(accountStrategyId);
            return saved;
        }
        return self.create(accountStrategyId, "default", resolved, /*activate=*/true, /*sourceBacktestRunId=*/null, updatedBy);
    }

    // ── CRUD (controllers / admin) ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<StrategyParam> findActive(UUID accountStrategyId) {
        if (accountStrategyId == null) return Optional.empty();
        return repository.findActiveByAccountStrategyId(accountStrategyId);
    }

    /**
     * Lookup by {@code paramId}. Includes soft-deleted rows — UI must filter.
     */
    @Transactional(readOnly = true)
    public Optional<StrategyParam> findById(UUID paramId) {
        if (paramId == null) return Optional.empty();
        return repository.findById(paramId);
    }

    /**
     * Live (non-deleted) presets for an account_strategy, ordered active-first
     * then by creation time.
     */
    @Transactional(readOnly = true)
    public List<StrategyParam> listByAccountStrategy(UUID accountStrategyId) {
        if (accountStrategyId == null) return List.of();
        return repository.findLiveByAccountStrategyId(accountStrategyId);
    }

    /**
     * Create a new preset. Caller decides whether it should also become active
     * via {@code activate} flag — when {@code true}, sibling actives are
     * cleared atomically.
     */
    @Transactional
    public StrategyParam create(UUID accountStrategyId,
                                String name,
                                Map<String, Object> overrides,
                                boolean activate,
                                UUID sourceBacktestRunId,
                                String createdBy) {
        if (accountStrategyId == null) {
            throw new IllegalArgumentException("accountStrategyId is required");
        }
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }

        StrategyParam entity = StrategyParam.builder()
                .paramId(UUID.randomUUID())
                .accountStrategyId(accountStrategyId)
                .name(trimmedName)
                .paramOverrides(overrides == null ? new HashMap<>() : new HashMap<>(overrides))
                .active(activate)
                .deleted(false)
                .sourceBacktestRunId(sourceBacktestRunId)
                .build();
        entity.setCreatedBy(createdBy);
        entity.setUpdatedBy(createdBy);

        if (activate) {
            // Clear siblings BEFORE the INSERT so the partial unique index
            // doesn't trip mid-transaction.
            repository.deactivateOthers(accountStrategyId, entity.getParamId());
        }
        StrategyParam saved = repository.save(entity);
        if (activate) {
            evictActiveCacheAfterCommit(accountStrategyId);
        }

        log.info("strategy_param CREATE: paramId={} accountStrategyId={} name='{}' active={} sourceBacktestRunId={} by={}",
                saved.getParamId(), accountStrategyId, trimmedName, activate, sourceBacktestRunId, createdBy);
        return saved;
    }

    /**
     * Update a preset's mutable fields. Activation flips are handled by
     * {@link #activate} / {@link #deactivate} — this method never touches
     * {@code active}.
     */
    @Transactional
    public StrategyParam update(UUID paramId,
                                String name,
                                Map<String, Object> overrides,
                                String updatedBy) {
        StrategyParam entity = repository.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        if (entity.isDeleted()) {
            throw new IllegalStateException("Cannot update soft-deleted preset paramId=" + paramId);
        }

        if (name != null) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("name cannot be blank");
            }
            entity.setName(trimmed);
        }
        if (overrides != null) {
            entity.setParamOverrides(new HashMap<>(overrides));
        }
        entity.setUpdatedBy(updatedBy);

        StrategyParam saved = repository.save(entity);
        if (saved.isActive()) {
            // Active preset's contents changed — invalidate the hot-path cache.
            evictActiveCacheAfterCommit(saved.getAccountStrategyId());
        }
        log.info("strategy_param UPDATE: paramId={} by={}", paramId, updatedBy);
        return saved;
    }

    /**
     * Atomically promote the target preset to active and clear any sibling
     * active. Idempotent — re-activating an already-active preset is a no-op
     * write (still updates updated_by/updated_time).
     */
    @Transactional
    public StrategyParam activate(UUID paramId, String updatedBy) {
        StrategyParam entity = repository.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        if (entity.isDeleted()) {
            throw new IllegalStateException(
                    "Cannot activate soft-deleted preset paramId=" + paramId);
        }

        UUID accountStrategyId = entity.getAccountStrategyId();
        if (!entity.isActive()) {
            long openTrades = tradesRepository.countOpenByAccountStrategyId(accountStrategyId);
            if (openTrades > 0) {
                throw new StrategyHasOpenTradesException(
                        "Cannot activate preset \"" + entity.getName() + "\" — account_strategy has "
                                + openTrades + " open trade(s). Close positions before switching presets.");
            }
        }
        repository.deactivateOthers(accountStrategyId, paramId);
        entity.setActive(true);
        entity.setUpdatedBy(updatedBy);
        StrategyParam saved = repository.save(entity);
        evictActiveCacheAfterCommit(accountStrategyId);

        log.info("strategy_param ACTIVATE: paramId={} accountStrategyId={} by={}",
                paramId, accountStrategyId, updatedBy);
        return saved;
    }

    /**
     * Clear the active flag on the preset. Leaves the account_strategy with no
     * active preset — live execution will fall back to defaults until another
     * preset is activated.
     */
    @Transactional
    public StrategyParam deactivate(UUID paramId, String updatedBy) {
        StrategyParam entity = repository.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        if (!entity.isActive()) {
            return entity;
        }
        entity.setActive(false);
        entity.setUpdatedBy(updatedBy);
        StrategyParam saved = repository.save(entity);
        evictActiveCacheAfterCommit(entity.getAccountStrategyId());

        log.info("strategy_param DEACTIVATE: paramId={} accountStrategyId={} by={}",
                paramId, entity.getAccountStrategyId(), updatedBy);
        return saved;
    }

    /**
     * Soft-delete a preset. The row stays in the table so historical backtests
     * targeting it by {@code paramId} continue to resolve. If the preset was
     * active, it is also deactivated atomically.
     */
    @Transactional
    public void softDelete(UUID paramId, String updatedBy) {
        StrategyParam entity = repository.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        if (entity.isDeleted()) return;

        boolean wasActive = entity.isActive();
        entity.setActive(false);
        entity.setDeleted(true);
        entity.setDeletedAt(LocalDateTime.now());
        entity.setUpdatedBy(updatedBy);
        repository.save(entity);
        if (wasActive) {
            evictActiveCacheAfterCommit(entity.getAccountStrategyId());
        }
        log.info("strategy_param SOFT_DELETE: paramId={} accountStrategyId={} wasActive={} by={}",
                paramId, entity.getAccountStrategyId(), wasActive, updatedBy);
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    static String activeCacheKey(UUID accountStrategyId) {
        return ACTIVE_CACHE_KEY_PREFIX + accountStrategyId;
    }

    private void evictActiveCache(UUID accountStrategyId) {
        try {
            redisTemplate.delete(activeCacheKey(accountStrategyId));
        } catch (Exception e) {
            log.warn("Failed to evict active strategy_param cache for accountStrategyId={}: {}",
                    accountStrategyId, e.getMessage());
        }
    }

    /**
     * Eviction must wait for transaction commit — otherwise a concurrent
     * reader could repopulate the cache from pre-commit DB state, giving a
     * stale view after the writer's transaction lands.
     */
    private void evictActiveCacheAfterCommit(UUID accountStrategyId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictActiveCache(accountStrategyId);
                }
            });
        } else {
            evictActiveCache(accountStrategyId);
        }
    }
}
