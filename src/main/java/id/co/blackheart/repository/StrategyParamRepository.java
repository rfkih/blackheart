package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Saved-preset repository for {@link StrategyParam} (V29 1:N schema).
 *
 * <p>Most queries filter {@code is_deleted = false} because soft-deleted presets
 * are invisible to UI listings and live execution. The "by id" lookup
 * deliberately does NOT filter {@code is_deleted}, so historical backtests can
 * resolve a soft-deleted preset by its {@code param_id}.
 */
@Repository
public interface StrategyParamRepository extends JpaRepository<StrategyParam, UUID> {

    /**
     * Hot path: live trading resolves the active preset for an account_strategy.
     * Excludes soft-deleted rows.
     */
    @Query("SELECT p FROM StrategyParam p " +
           "WHERE p.accountStrategyId = :accountStrategyId " +
           "  AND p.active = true " +
           "  AND p.deleted = false")
    Optional<StrategyParam> findActiveByAccountStrategyId(
            @Param("accountStrategyId") UUID accountStrategyId);

    /**
     * UI listing path: every live (non-deleted) preset for an account_strategy,
     * deterministic order so the frontend doesn't shuffle.
     */
    @Query("SELECT p FROM StrategyParam p " +
           "WHERE p.accountStrategyId = :accountStrategyId " +
           "  AND p.deleted = false " +
           "ORDER BY p.active DESC, p.createdTime ASC")
    List<StrategyParam> findLiveByAccountStrategyId(
            @Param("accountStrategyId") UUID accountStrategyId);

    /**
     * Backtest path: resolve a specific preset by id. Includes soft-deleted rows
     * so historical backtests stay reproducible after an operator deletes a
     * preset.
     */
    @Override
    Optional<StrategyParam> findById(UUID paramId);

    /**
     * Atomic active flip — used by {@code activate(paramId)} to clear other
     * actives in the same account_strategy before flipping the target. Bulk
     * UPDATE bypasses Hibernate's first-level cache so the partial unique
     * index doesn't trip during the flip.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StrategyParam p SET p.active = false " +
           "WHERE p.accountStrategyId = :accountStrategyId " +
           "  AND p.active = true " +
           "  AND p.paramId <> :exceptParamId")
    int deactivateOthers(@Param("accountStrategyId") UUID accountStrategyId,
                         @Param("exceptParamId") UUID exceptParamId);
}
