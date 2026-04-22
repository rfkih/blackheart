package id.co.blackheart.repository;

import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.projection.EnabledAccountStrategyProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountStrategyRepository extends JpaRepository<AccountStrategy, UUID> {

    /**
     * Looks up a row by the same tuple that backs the {@code uq_account_strategy}
     * unique constraint — {@code (account_id, strategy_definition_id, symbol, interval_name)}.
     *
     * <p>Crucially this DOES NOT filter out soft-deleted rows: the constraint
     * itself is not partial, so a previously-deleted row still owns the key
     * and would block a naive INSERT. Callers use this to either report a
     * friendly 409 (if the hit is active) or revive the existing row
     * (if the hit is soft-deleted) without mutating the primary key —
     * preserving any trade history that references it.
     */
    @Query(value = """
        SELECT acs.*
        FROM account_strategy acs
        WHERE acs.account_id = :accountId
          AND acs.strategy_definition_id = :strategyDefinitionId
          AND acs.symbol = :symbol
          AND acs.interval_name = :intervalName
        """, nativeQuery = true)
    Optional<AccountStrategy> findByUniqueKey(
            @Param("accountId") UUID accountId,
            @Param("strategyDefinitionId") UUID strategyDefinitionId,
            @Param("symbol") String symbol,
            @Param("intervalName") String intervalName);


    @Query(value = """
        SELECT acs.*
        FROM account_strategy acs
        WHERE acs.enabled = true
          AND acs.is_deleted = false
          AND acs.interval_name = :interval
        ORDER BY acs.priority_order ASC, acs.created_time ASC
        """, nativeQuery = true)
    List<AccountStrategy> findByEnabledTrueAndIntervalName(@Param("interval") String interval);

    @Query(value = """
            SELECT
                acs.account_id AS accountId,
                acs.account_strategy_id AS accountStrategyId
            FROM account_strategy acs
            WHERE acs.enabled = true
              AND acs.is_deleted = false
            """, nativeQuery = true)
    List<EnabledAccountStrategyProjection> findAllEnabledStrategyRefs();

    @Query(value = """
            SELECT acs.*
            FROM account_strategy acs
            WHERE acs.account_id = :accountId
              AND acs.is_deleted = false
            ORDER BY acs.priority_order ASC, acs.created_time ASC
            """, nativeQuery = true)
    List<AccountStrategy> findByAccountId(@Param("accountId") UUID accountId);

}