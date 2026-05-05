package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface BacktestRunRepository extends JpaRepository<BacktestRun, UUID> {

    /**
     * Kept for callers that still want the simple, unfiltered ordered list.
     * New callers should use {@link #findFiltered} which carries filter + sort
     * parameters the run-history UI relies on.
     */
    @Query(value = """
            SELECT * FROM backtest_run
            ORDER BY created_time DESC
            LIMIT :limitVal OFFSET :offsetVal
            """, nativeQuery = true)
    List<BacktestRun> findAllOrderByCreatedTimeDesc(
            @Param("limitVal") int limitVal,
            @Param("offsetVal") int offsetVal
    );

    @Query(value = "SELECT COUNT(*) FROM backtest_run", nativeQuery = true)
    long countAll();

    /**
     * Filtered + sorted + paginated list.
     *
     * <p>Visibility rules:
     * <ul>
     *   <li>RESEARCHER runs ({@code triggered_by = 'RESEARCHER'}) are visible to every
     *       authenticated user regardless of who submitted them.</li>
     *   <li>USER runs ({@code triggered_by = 'USER'}) are scoped to their owner via
     *       {@code user_id = :userId}.</li>
     * </ul>
     *
     * <p>Every filter param is nullable — passing {@code null} disables that
     * clause via the {@code CAST(:param AS TEXT) IS NULL} idiom. String
     * filters do case-insensitive exact match (status, interval) or prefix
     * match (symbol, strategyCode) — Postgres ILIKE.
     *
     * <p>{@code triggeredBy} narrows to a specific origin: pass {@code "USER"} to
     * show only the caller's own runs, {@code "RESEARCHER"} to show only
     * researcher-submitted runs, or {@code null} for all visible runs.
     *
     * <p>{@code sortColumn} is validated by the service layer against a
     * whitelist before it reaches this query — never pass a user-supplied
     * value through directly. {@code sortDir} must be {@code "ASC"} or
     * {@code "DESC"} (anything else falls back to DESC at the SQL level via
     * the CASE).
     *
     * <p>Ordering by nullable metric columns (return_pct, sharpe_ratio, etc.)
     * uses {@code NULLS LAST} so RUNNING rows without metrics drop to the
     * bottom of an ascending/descending sort rather than pretending to be the
     * worst-performing runs.
     */
    @Query(value = """
            SELECT * FROM backtest_run
            WHERE (user_id = :userId OR triggered_by = 'RESEARCHER')
              AND (CAST(:triggeredBy AS TEXT) IS NULL OR UPPER(triggered_by) = UPPER(CAST(:triggeredBy AS TEXT)))
              AND (CAST(:status AS TEXT) IS NULL OR UPPER(status) = UPPER(CAST(:status AS TEXT)))
              AND (CAST(:strategyCode AS TEXT) IS NULL OR strategy_code ILIKE CONCAT(CAST(:strategyCode AS TEXT), '%'))
              AND (CAST(:symbol AS TEXT) IS NULL OR asset ILIKE CONCAT(CAST(:symbol AS TEXT), '%'))
              AND (CAST(:intervalName AS TEXT) IS NULL OR interval_name = CAST(:intervalName AS TEXT))
              AND (CAST(:fromDate AS TIMESTAMP) IS NULL OR created_time >= CAST(:fromDate AS TIMESTAMP))
              AND (CAST(:toDate AS TIMESTAMP) IS NULL OR created_time <= CAST(:toDate AS TIMESTAMP))
            ORDER BY
              CASE WHEN :sortColumn = 'createdAt'      AND UPPER(:sortDir) = 'ASC'  THEN created_time END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'createdAt'      AND UPPER(:sortDir) = 'DESC' THEN created_time END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'returnPct'      AND UPPER(:sortDir) = 'ASC'  THEN return_pct   END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'returnPct'      AND UPPER(:sortDir) = 'DESC' THEN return_pct   END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'sharpe'         AND UPPER(:sortDir) = 'ASC'  THEN sharpe_ratio END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'sharpe'         AND UPPER(:sortDir) = 'DESC' THEN sharpe_ratio END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'maxDrawdownPct' AND UPPER(:sortDir) = 'ASC'  THEN max_drawdown_pct END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'maxDrawdownPct' AND UPPER(:sortDir) = 'DESC' THEN max_drawdown_pct END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'totalTrades'    AND UPPER(:sortDir) = 'ASC'  THEN total_trades END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'totalTrades'    AND UPPER(:sortDir) = 'DESC' THEN total_trades END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'winRate'        AND UPPER(:sortDir) = 'ASC'  THEN win_rate END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'winRate'        AND UPPER(:sortDir) = 'DESC' THEN win_rate END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'status'         AND UPPER(:sortDir) = 'ASC'  THEN status END ASC,
              CASE WHEN :sortColumn = 'status'         AND UPPER(:sortDir) = 'DESC' THEN status END DESC,
              CASE WHEN :sortColumn = 'symbol'         AND UPPER(:sortDir) = 'ASC'  THEN asset END ASC,
              CASE WHEN :sortColumn = 'symbol'         AND UPPER(:sortDir) = 'DESC' THEN asset END DESC,
              CASE WHEN :sortColumn = 'strategyCode'   AND UPPER(:sortDir) = 'ASC'  THEN strategy_code END ASC,
              CASE WHEN :sortColumn = 'strategyCode'   AND UPPER(:sortDir) = 'DESC' THEN strategy_code END DESC,
              created_time DESC
            LIMIT :limitVal OFFSET :offsetVal
            """, nativeQuery = true)
    List<BacktestRun> findFiltered(
            @Param("userId") UUID userId,
            @Param("triggeredBy") String triggeredBy,
            @Param("status") String status,
            @Param("strategyCode") String strategyCode,
            @Param("symbol") String symbol,
            @Param("intervalName") String intervalName,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("sortColumn") String sortColumn,
            @Param("sortDir") String sortDir,
            @Param("limitVal") int limitVal,
            @Param("offsetVal") int offsetVal
    );

    @Query(value = """
            SELECT COUNT(*) FROM backtest_run
            WHERE (user_id = :userId OR triggered_by = 'RESEARCHER')
              AND (CAST(:triggeredBy AS TEXT) IS NULL OR UPPER(triggered_by) = UPPER(CAST(:triggeredBy AS TEXT)))
              AND (CAST(:status AS TEXT) IS NULL OR UPPER(status) = UPPER(CAST(:status AS TEXT)))
              AND (CAST(:strategyCode AS TEXT) IS NULL OR strategy_code ILIKE CONCAT(CAST(:strategyCode AS TEXT), '%'))
              AND (CAST(:symbol AS TEXT) IS NULL OR asset ILIKE CONCAT(CAST(:symbol AS TEXT), '%'))
              AND (CAST(:intervalName AS TEXT) IS NULL OR interval_name = CAST(:intervalName AS TEXT))
              AND (CAST(:fromDate AS TIMESTAMP) IS NULL OR created_time >= CAST(:fromDate AS TIMESTAMP))
              AND (CAST(:toDate AS TIMESTAMP) IS NULL OR created_time <= CAST(:toDate AS TIMESTAMP))
            """, nativeQuery = true)
    long countFiltered(
            @Param("userId") UUID userId,
            @Param("triggeredBy") String triggeredBy,
            @Param("status") String status,
            @Param("strategyCode") String strategyCode,
            @Param("symbol") String symbol,
            @Param("intervalName") String intervalName,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    /**
     * Research-log feed for the /research dashboard. Returns COMPLETED runs
     * that have an analysis snapshot, optionally narrowed by strategy / asset /
     * interval / free-text search, with configurable sort and pagination.
     *
     * <p>{@code userId} is matched permissively — rows with {@code user_id IS NULL}
     * (legacy / orchestrator-driven runs) are visible to every admin caller.
     *
     * <p>Each filter is nullable. {@code CAST(:p AS TEXT)} pins the JDBC type to
     * avoid Hibernate inferring {@code bytea} for nullable strings on Postgres.
     *
     * <p>{@code sortColumn} must be validated against a whitelist by the caller
     * before being passed here. {@code sortDir} must be {@code "ASC"} or
     * {@code "DESC"} — the CASE fallback lands on {@code created_time DESC}.
     */
    @Query(value = """
            SELECT * FROM backtest_run
            WHERE status = 'COMPLETED'
              AND analysis_snapshot IS NOT NULL
              AND analysis_snapshot <> ''
              AND (user_id IS NULL OR user_id = :userId)
              AND (CAST(:strategyCode AS TEXT) IS NULL
                   OR UPPER(strategy_code) = UPPER(CAST(:strategyCode AS TEXT)))
              AND (CAST(:asset AS TEXT) IS NULL
                   OR UPPER(asset) = UPPER(CAST(:asset AS TEXT)))
              AND (CAST(:intervalName AS TEXT) IS NULL
                   OR interval_name = CAST(:intervalName AS TEXT))
              AND (CAST(:search AS TEXT) IS NULL
                   OR strategy_code ILIKE CONCAT('%', CAST(:search AS TEXT), '%')
                   OR asset         ILIKE CONCAT('%', CAST(:search AS TEXT), '%'))
            ORDER BY
              CASE WHEN :sortColumn = 'createdAt'       AND UPPER(:sortDir) = 'ASC'  THEN created_time     END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'createdAt'       AND UPPER(:sortDir) = 'DESC' THEN created_time     END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'tradeCount'      AND UPPER(:sortDir) = 'ASC'  THEN total_trades     END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'tradeCount'      AND UPPER(:sortDir) = 'DESC' THEN total_trades     END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'winRate'         AND UPPER(:sortDir) = 'ASC'  THEN win_rate         END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'winRate'         AND UPPER(:sortDir) = 'DESC' THEN win_rate         END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'profitFactor'    AND UPPER(:sortDir) = 'ASC'  THEN profit_factor    END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'profitFactor'    AND UPPER(:sortDir) = 'DESC' THEN profit_factor    END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'maxDrawdown'     AND UPPER(:sortDir) = 'ASC'  THEN max_drawdown_pct END ASC  NULLS LAST,
              CASE WHEN :sortColumn = 'maxDrawdown'     AND UPPER(:sortDir) = 'DESC' THEN max_drawdown_pct END DESC NULLS LAST,
              CASE WHEN :sortColumn = 'strategyCode'    AND UPPER(:sortDir) = 'ASC'  THEN strategy_code   END ASC,
              CASE WHEN :sortColumn = 'strategyCode'    AND UPPER(:sortDir) = 'DESC' THEN strategy_code   END DESC,
              CASE WHEN :sortColumn = 'asset'           AND UPPER(:sortDir) = 'ASC'  THEN asset            END ASC,
              CASE WHEN :sortColumn = 'asset'           AND UPPER(:sortDir) = 'DESC' THEN asset            END DESC,
              CASE WHEN :sortColumn = 'strategyVersion' AND UPPER(:sortDir) = 'ASC'  THEN strategy_version END ASC,
              CASE WHEN :sortColumn = 'strategyVersion' AND UPPER(:sortDir) = 'DESC' THEN strategy_version END DESC,
              created_time DESC NULLS LAST
            """,
           countQuery = """
            SELECT COUNT(*) FROM backtest_run
            WHERE status = 'COMPLETED'
              AND analysis_snapshot IS NOT NULL
              AND analysis_snapshot <> ''
              AND (user_id IS NULL OR user_id = :userId)
              AND (CAST(:strategyCode AS TEXT) IS NULL
                   OR UPPER(strategy_code) = UPPER(CAST(:strategyCode AS TEXT)))
              AND (CAST(:asset AS TEXT) IS NULL
                   OR UPPER(asset) = UPPER(CAST(:asset AS TEXT)))
              AND (CAST(:intervalName AS TEXT) IS NULL
                   OR interval_name = CAST(:intervalName AS TEXT))
              AND (CAST(:search AS TEXT) IS NULL
                   OR strategy_code ILIKE CONCAT('%', CAST(:search AS TEXT), '%')
                   OR asset         ILIKE CONCAT('%', CAST(:search AS TEXT), '%'))
            """,
           nativeQuery = true)
    Page<BacktestRun> findResearchLog(
            @Param("userId") UUID userId,
            @Param("strategyCode") String strategyCode,
            @Param("asset") String asset,
            @Param("intervalName") String intervalName,
            @Param("search") String search,
            @Param("sortColumn") String sortColumn,
            @Param("sortDir") String sortDir,
            Pageable pageable);

    /**
     * Fetch a single run by id visible to the caller. RESEARCHER runs are
     * accessible to every authenticated user; USER runs are scoped to their
     * owner. Returns empty for runs that don't match either condition so
     * callers collapse "not found" and "not yours" into the same 404.
     */
    @Query(value = """
            SELECT * FROM backtest_run
            WHERE backtest_run_id = :id
              AND (user_id = :userId OR triggered_by = 'RESEARCHER')
            """, nativeQuery = true)
    java.util.Optional<BacktestRun> findByIdAndUserId(
            @Param("id") UUID id,
            @Param("userId") UUID userId);

    /**
     * Targeted marker-only UPDATE for the holdout flag. Used by
     * {@code ResearchSweepService.evaluateHoldout} to avoid clobbering the
     * async worker's progress / status writes — a full {@code save(row)}
     * would write back every field of a possibly-stale entity.
     *
     * <p>Returns the number of rows updated. The unique partial index
     * {@code idx_backtest_run_holdout_per_sweep} additionally enforces
     * "at most one holdout per sweep" at the DB level.
     */
    /**
     * Recent completed runs for a strategy that have enough trades to be
     * statistically meaningful. Used by {@code KellySizingService} to compute
     * a PSR-discounted Kelly fraction from backtest statistics.
     *
     * <p>Ordered most-recent first; {@code limit} caps how many runs we pull
     * in — 5 recent runs is plenty for a stable Kelly estimate and avoids
     * stale parameter regimes that no longer reflect current market conditions.
     *
     * <p>Matches both single-strategy runs ({@code account_strategy_id} column)
     * and multi-strategy runs where the target id appears in the
     * {@code strategy_account_strategy_ids} JSONB map. The map shape is
     * {@code {strategyCode -> accountStrategyId}} where the key is unknown,
     * so jsonb {@code @>} can't be used (it requires structurally-identical
     * fragments). A text-cast + LIKE substring scan covers the "value anywhere"
     * case correctly. The row count on {@code backtest_run} is small enough
     * that a sequential scan on the JSONB branch is acceptable for the
     * interactive Kelly recompute path.
     *
     * <p>Without the JSONB branch, strategies that only show up in multi-
     * strategy backtests would produce zero qualifying runs and Kelly would
     * silently no-op.
     *
     * <p>{@code uuidText} must be the same UUID as {@code accountStrategyId}
     * rendered as its canonical lowercase string. Pass via the
     * {@link #findRecentCompletedByAccountStrategyId(UUID, int, int)} default
     * wrapper to enforce that contract.
     */
    @Query(value = """
            SELECT * FROM backtest_run
            WHERE (account_strategy_id = :accountStrategyId
                   OR strategy_account_strategy_ids::text ILIKE CONCAT('%', :uuidText, '%'))
              AND status = 'COMPLETED'
              AND total_trades >= :minTrades
              AND win_rate IS NOT NULL
              AND avg_win IS NOT NULL
              AND avg_loss IS NOT NULL
            ORDER BY created_time DESC
            LIMIT :limitVal
            """, nativeQuery = true)
    List<BacktestRun> findRecentCompletedByAccountStrategyIdValueScan(
            @Param("accountStrategyId") UUID accountStrategyId,
            @Param("uuidText") String uuidText,
            @Param("minTrades") int minTrades,
            @Param("limitVal") int limitVal
    );

    /** Convenience wrapper — derives the lowercase UUID text needle from the id. */
    default List<BacktestRun> findRecentCompletedByAccountStrategyId(
            UUID accountStrategyId, int minTrades, int limitVal) {
        return findRecentCompletedByAccountStrategyIdValueScan(
                accountStrategyId, accountStrategyId.toString(), minTrades, limitVal);
    }

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE backtest_run
               SET is_holdout_run = TRUE,
                   holdout_for_sweep_id = :sweepId
             WHERE backtest_run_id = :runId
               AND is_holdout_run = FALSE
            """, nativeQuery = true)
    int markAsHoldoutRun(
            @Param("runId") UUID runId,
            @Param("sweepId") UUID sweepId);
}
