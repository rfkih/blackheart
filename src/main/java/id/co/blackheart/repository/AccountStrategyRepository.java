package id.co.blackheart.repository;

import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.projection.EnabledAccountStrategyProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountStrategyRepository extends JpaRepository<AccountStrategy, UUID> {

    /**
     * Pessimistic-write lock variant of findById. Used by
     * {@code StrategyPromotionService.promote()} to serialize concurrent
     * state transitions. Without this, two concurrent admin calls could
     * both read the same currentState and both insert promotion-log rows
     * for the same transition (Bug 3 fix, 2026-04-28).
     *
     * <p>The lock is held for the duration of the calling
     * {@code @Transactional} method; releases on commit/rollback.
     * Concurrent callers wait at the SQL layer, not in Java code.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountStrategy a WHERE a.accountStrategyId = :id")
    Optional<AccountStrategy> findByIdForUpdate(@Param("id") UUID id);

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
          AND acs.is_deleted = false
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

    /**
     * PROMOTED state = enabled AND not simulated AND not soft-deleted.
     * Used by {@code PnlDeviationAlertService} to scope deviation checks
     * to strategies actually trading real capital.
     */
    @Query(value = """
            SELECT acs.*
            FROM account_strategy acs
            WHERE acs.enabled = true
              AND acs.simulated = false
              AND acs.is_deleted = false
            """, nativeQuery = true)
    List<AccountStrategy> findAllPromoted();

    @Query(value = """
            SELECT acs.*
            FROM account_strategy acs
            WHERE acs.account_id = :accountId
              AND acs.is_deleted = false
            ORDER BY acs.priority_order ASC, acs.created_time ASC
            """, nativeQuery = true)
    List<AccountStrategy> findByAccountId(@Param("accountId") UUID accountId);

    /**
     * All presets that share the given tuple. Used to show preset groupings
     * in the UI and to flip siblings off when a new preset is activated.
     * Includes both enabled and disabled rows but excludes soft-deletes.
     */
    @Query(value = """
            SELECT acs.*
            FROM account_strategy acs
            WHERE acs.account_id = :accountId
              AND acs.strategy_definition_id = :strategyDefinitionId
              AND acs.symbol = :symbol
              AND acs.interval_name = :intervalName
              AND acs.is_deleted = false
            ORDER BY acs.enabled DESC, acs.priority_order ASC, acs.created_time ASC
            """, nativeQuery = true)
    List<AccountStrategy> findPresetsForTuple(
            @Param("accountId") UUID accountId,
            @Param("strategyDefinitionId") UUID strategyDefinitionId,
            @Param("symbol") String symbol,
            @Param("intervalName") String intervalName);

    /**
     * Returns the currently-active preset (if any) for the tuple. Exactly one
     * row can match — the partial unique index
     * {@code uq_account_strategy_active_preset} enforces it.
     */
    @Query(value = """
            SELECT acs.*
            FROM account_strategy acs
            WHERE acs.account_id = :accountId
              AND acs.strategy_definition_id = :strategyDefinitionId
              AND acs.symbol = :symbol
              AND acs.interval_name = :intervalName
              AND acs.is_deleted = false
              AND acs.enabled = true
            """, nativeQuery = true)
    Optional<AccountStrategy> findActivePreset(
            @Param("accountId") UUID accountId,
            @Param("strategyDefinitionId") UUID strategyDefinitionId,
            @Param("symbol") String symbol,
            @Param("intervalName") String intervalName);

    /**
     * V66 — List variant of {@link #findActivePreset}. Used by
     * {@code AccountStrategyService.activateStrategy} when V64-style seeds
     * have left more than one enabled row on the same tuple (e.g. paired
     * LONG/SHORT TEST rows). The single-result variant throws
     * NonUniqueResultException in that case; this variant returns every
     * matching row so the activate path can deactivate ALL siblings before
     * enabling the target — restoring the "one active per tuple" invariant
     * even when prior writes bypassed it.
     */
    @Query(value = """
            SELECT acs.*
            FROM account_strategy acs
            WHERE acs.account_id = :accountId
              AND acs.strategy_definition_id = :strategyDefinitionId
              AND acs.symbol = :symbol
              AND acs.interval_name = :intervalName
              AND acs.is_deleted = false
              AND acs.enabled = true
            """, nativeQuery = true)
    List<AccountStrategy> findActivePresets(
            @Param("accountId") UUID accountId,
            @Param("strategyDefinitionId") UUID strategyDefinitionId,
            @Param("symbol") String symbol,
            @Param("intervalName") String intervalName);

    /**
     * V54 — every row visible to the calling user: their own (any visibility)
     * plus every other user's PUBLIC rows. The `visibility='PUBLIC'` half is
     * what surfaces the research-agent's strategy catalogue to all tenants
     * for browse-and-clone. Soft-deleted rows are excluded on both sides.
     *
     * <p>No DISTINCT needed: the FK guarantees one accounts row per
     * account_strategy row, so the join can never multiply.
     */
    @Query(value = """
            SELECT acs.*
            FROM account_strategy acs
            JOIN accounts a ON a.account_id = acs.account_id
            WHERE acs.is_deleted = false
              AND (a.user_id = :userId OR acs.visibility = 'PUBLIC')
            ORDER BY acs.priority_order ASC, acs.created_time ASC
            """, nativeQuery = true)
    List<AccountStrategy> findAllVisibleToUser(@Param("userId") UUID userId);

    /**
     * Highest existing priority_order for the account — used by clone to slot
     * the new row at the bottom of the user's preset list rather than colliding
     * with whatever priority the source row had.
     */
    @Query(value = """
            SELECT COALESCE(MAX(acs.priority_order), 0)
            FROM account_strategy acs
            WHERE acs.account_id = :accountId
              AND acs.is_deleted = false
            """, nativeQuery = true)
    Integer findMaxPriorityOrderByAccountId(@Param("accountId") UUID accountId);

    /**
     * V54 — same-tuple lookup that *includes* soft-deleted rows. Used by clone
     * pre-check: a soft-deleted row still occupies the unique-constraint slot
     * (the constraint isn't partial), so an INSERT against the same tuple
     * trips a DataIntegrityViolation. We surface a friendlier "previously
     * deleted, restore it instead" message before that happens.
     */
    @Query(value = """
        SELECT acs.*
        FROM account_strategy acs
        WHERE acs.account_id = :accountId
          AND acs.strategy_definition_id = :strategyDefinitionId
          AND acs.symbol = :symbol
          AND acs.interval_name = :intervalName
        ORDER BY acs.is_deleted ASC, acs.created_time DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<AccountStrategy> findByUniqueKeyIncludingDeleted(
            @Param("accountId") UUID accountId,
            @Param("strategyDefinitionId") UUID strategyDefinitionId,
            @Param("symbol") String symbol,
            @Param("intervalName") String intervalName);
}