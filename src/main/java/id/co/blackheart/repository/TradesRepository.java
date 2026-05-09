package id.co.blackheart.repository;

import id.co.blackheart.model.Trades;
import id.co.blackheart.projection.TradeAnomalyProjection;
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

    /**
     * Open + partially-closed trades for a single account. Used by the
     * account-delete flow to refuse removal of an account with live exposure.
     */
    @Query(value = """
            SELECT COUNT(*) FROM trades t
            WHERE t.account_id = :accountId
              AND t.status IN ('OPEN', 'PARTIALLY_CLOSED')
            """, nativeQuery = true)
    long countOpenByAccountId(@Param("accountId") UUID accountId);

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

    @Query(value = """
            SELECT * FROM trades t
            WHERE t.status IN ('OPEN', 'PARTIALLY_CLOSED')
            ORDER BY t.entry_time DESC
            """, nativeQuery = true)
    List<Trades> findAllOpen();

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

    /**
     * Closed trades for one strategy with exit_time within the given
     * window, ordered ASC. Used by RiskGuardService to walk the cumulative
     * P&L sequence and compute peak-to-trough drawdown.
     */
    @Query(value = """
            SELECT * FROM trades t
            WHERE t.account_strategy_id = :accountStrategyId
              AND t.status = 'CLOSED'
              AND t.exit_time IS NOT NULL
              AND t.exit_time >= :since
            ORDER BY t.exit_time ASC
            """, nativeQuery = true)
    List<Trades> findClosedByAccountStrategyIdSince(
            @Param("accountStrategyId") UUID accountStrategyId,
            @Param("since") LocalDateTime since
    );

    /**
     * Concurrent-direction count across every strategy on the account.
     * Used by RiskGuardService to enforce {@code Account.maxConcurrentLongs}
     * / {@code maxConcurrentShorts} before approving a new entry.
     */
    @Query(value = """
            SELECT COUNT(*) FROM trades t
            WHERE t.account_id = :accountId
              AND UPPER(t.side) = UPPER(:side)
              AND t.status IN ('OPEN', 'PARTIALLY_CLOSED')
            """, nativeQuery = true)
    long countOpenByAccountIdAndSide(
            @Param("accountId") UUID accountId,
            @Param("side") String side
    );

    /**
     * All closed trades for one symbol that have intended-entry intent
     * recorded (Phase 2c). Used by SlippageCalibrationService to fit a
     * realized-fill cost model from the user's own history rather than a
     * hardcoded default. Capped at the most-recent {@code limitVal} rows
     * to keep the service O(window) regardless of total history depth.
     */
    @Query(value = """
            SELECT * FROM trades t
            WHERE t.asset = :asset
              AND t.intended_entry_price IS NOT NULL
              AND t.avg_entry_price IS NOT NULL
              AND t.entry_time IS NOT NULL
            ORDER BY t.entry_time DESC
            LIMIT :limitVal
            """, nativeQuery = true)
    List<Trades> findRecentWithIntent(
            @Param("asset") String asset,
            @Param("limitVal") int limitVal
    );

    /**
     * Trade-state anomalies for the admin reconciliation panel. Returns one row
     * per non-CLOSED parent trade together with its open-leg count, so the
     * service layer can classify each as:
     *
     * <ul>
     *   <li>{@code OPEN_NO_CHILDREN}      — parent OPEN, zero rows in
     *       trade_positions for the trade. Almost certainly a botched open.</li>
     *   <li>{@code OPEN_NO_OPEN_CHILDREN} — parent OPEN but every child CLOSED.
     *       The listener never flipped the parent — capital is "free" on the
     *       exchange but the row still blocks new entries via the per-strategy
     *       active-trade gate.</li>
     *   <li>{@code PARTIAL_NO_OPEN_CHILDREN} — PARTIALLY_CLOSED parent with no
     *       OPEN legs. Per CLAUDE.md domain invariant: not a tradable position;
     *       parent should be CLOSED.</li>
     * </ul>
     *
     * Open-trade count is small (single-digit in steady state) so the per-row
     * subquery is fine; if this ever explodes, replace with a join + GROUP BY.
     */
    @Query(value = """
            SELECT
                t.trade_id              AS tradeId,
                t.account_id            AS accountId,
                t.account_strategy_id   AS accountStrategyId,
                t.asset                 AS asset,
                t.interval              AS interval,
                t.side                  AS side,
                t.status                AS status,
                t.entry_time            AS entryTime,
                (SELECT COUNT(*) FROM trade_positions tp
                  WHERE tp.trade_id = t.trade_id)                          AS totalLegs,
                (SELECT COUNT(*) FROM trade_positions tp
                  WHERE tp.trade_id = t.trade_id AND tp.status = 'OPEN')   AS openLegs
            FROM trades t
            WHERE t.account_id IN (:accountIds)
              AND t.status IN ('OPEN', 'PARTIALLY_CLOSED')
            ORDER BY t.entry_time DESC
            """, nativeQuery = true)
    List<TradeAnomalyProjection> findAnomaliesByAccountIds(
            @Param("accountIds") List<UUID> accountIds
    );

}