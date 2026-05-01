package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Unified strategy_param repository — serves every strategy code through one table.
 * See StrategyParam Javadoc for the design rationale.
 */
@Repository
public interface StrategyParamRepository extends JpaRepository<StrategyParam, UUID> {

    /**
     * Most common access path. Returns empty when the account_strategy has no
     * overrides (use strategy defaults).
     */
    Optional<StrategyParam> findByAccountStrategyId(UUID accountStrategyId);

    /**
     * Reset to defaults — drop the override row entirely. Used by DELETE handlers
     * in the param controller. {@code @Modifying} bypasses Hibernate's first-level
     * cache so the cache-aside eviction in the service layer sees a true "no row" state.
     */
    @Modifying
    @Query("DELETE FROM StrategyParam p WHERE p.accountStrategyId = :accountStrategyId")
    int deleteByAccountStrategyId(@Param("accountStrategyId") UUID accountStrategyId);
}
