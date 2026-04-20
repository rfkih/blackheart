package id.co.blackheart.repository;

import id.co.blackheart.model.Trades;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradesRepository extends JpaRepository<Trades, UUID> {

    @Query(value = """
            SELECT *
            FROM trades t
            WHERE t.trade_id = :tradeId
            """, nativeQuery = true)
    Optional<Trades> findByTradeId(@Param("tradeId") UUID tradeId);

    @Query(value = """
            SELECT *
            FROM trades t
            WHERE t.account_id = :accountId
              AND t.account_strategy_id = :accountStrategyId
              AND t.asset = :asset
              AND t.interval = :interval
              AND t.status IN (:statuses)
            ORDER BY t.entry_time DESC
            """, nativeQuery = true)
    List<Trades> findAllActiveTrades(
            @Param("accountId") UUID accountId,
            @Param("accountStrategyId") UUID accountStrategyId,
            @Param("asset") String asset,
            @Param("interval") String interval,
            @Param("statuses") List<String> statuses
    );

    /**
     * Finds active trades across a group of account strategies (for multi-strategy orchestrator routing).
     */
    @Query(value = """
            SELECT *
            FROM trades t
            WHERE t.account_id = :accountId
              AND t.account_strategy_id IN (:accountStrategyIds)
              AND t.asset = :asset
              AND t.interval = :interval
              AND t.status IN (:statuses)
            ORDER BY t.entry_time DESC
            """, nativeQuery = true)
    List<Trades> findAllActiveTradesForStrategies(
            @Param("accountId") UUID accountId,
            @Param("accountStrategyIds") List<UUID> accountStrategyIds,
            @Param("asset") String asset,
            @Param("interval") String interval,
            @Param("statuses") List<String> statuses
    );

    @Query(value = """
            SELECT * FROM trades t
            WHERE t.account_id IN (:accountIds)
            ORDER BY t.entry_time DESC
            LIMIT :limitVal OFFSET :offsetVal
            """, nativeQuery = true)
    List<Trades> findByAccountIds(
            @Param("accountIds") List<UUID> accountIds,
            @Param("limitVal") int limitVal,
            @Param("offsetVal") int offsetVal
    );

    @Query(value = """
            SELECT * FROM trades t
            WHERE t.account_id IN (:accountIds)
              AND t.status = :status
            ORDER BY t.entry_time DESC
            LIMIT :limitVal OFFSET :offsetVal
            """, nativeQuery = true)
    List<Trades> findByAccountIdsAndStatus(
            @Param("accountIds") List<UUID> accountIds,
            @Param("status") String status,
            @Param("limitVal") int limitVal,
            @Param("offsetVal") int offsetVal
    );

    @Query(value = "SELECT COUNT(*) FROM trades WHERE account_id IN (:accountIds)", nativeQuery = true)
    long countByAccountIds(@Param("accountIds") List<UUID> accountIds);

    @Query(value = """
            SELECT COUNT(*) FROM trades t
            WHERE t.account_strategy_id = :accountStrategyId
              AND t.status IN ('OPEN', 'PARTIALLY_CLOSED')
            """, nativeQuery = true)
    long countOpenByAccountStrategyId(@Param("accountStrategyId") UUID accountStrategyId);

    @Query(value = "SELECT COUNT(*) FROM trades WHERE account_id IN (:accountIds) AND status = :status", nativeQuery = true)
    long countByAccountIdsAndStatus(
            @Param("accountIds") List<UUID> accountIds,
            @Param("status") String status
    );

    @Query(value = """
            SELECT * FROM trades t
            WHERE t.account_id IN (:accountIds)
              AND t.status = 'CLOSED'
              AND t.exit_time >= :from
              AND t.exit_time < :to
            ORDER BY t.exit_time DESC
            """, nativeQuery = true)
    List<Trades> findClosedInPeriodByAccountIds(
            @Param("accountIds") List<UUID> accountIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query(value = """
            SELECT * FROM trades t
            WHERE t.account_id IN (:accountIds)
              AND t.status IN ('OPEN', 'PARTIALLY_CLOSED')
            ORDER BY t.entry_time DESC
            """, nativeQuery = true)
    List<Trades> findOpenByAccountIds(@Param("accountIds") List<UUID> accountIds);

    /**
     * All closed trades for one account with exit_time ≤ cutoff, sorted ASCENDING.
     * Used by the equity-curve endpoint so cumulative realized PnL can be carried
     * across the requested window boundary (the equity at `from` already reflects
     * every trade closed before that moment).
     */
    @Query(value = """
            SELECT * FROM trades t
            WHERE t.account_id = :accountId
              AND t.status = 'CLOSED'
              AND t.exit_time IS NOT NULL
              AND t.exit_time <= :cutoff
            ORDER BY t.exit_time ASC
            """, nativeQuery = true)
    List<Trades> findClosedByAccountIdUpTo(
            @Param("accountId") UUID accountId,
            @Param("cutoff") LocalDateTime cutoff
    );

}