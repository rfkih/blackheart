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
     * Filtered + sorted + paginated list, <b>scoped to the authenticated user</b>.
     *
     * <p>Every filter param is nullable — passing {@code null} disables that
     * clause via the {@code CAST(:param AS TEXT) IS NULL} idiom. String
     * filters do case-insensitive exact match (status, interval) or prefix
     * match (symbol, strategyCode) — Postgres ILIKE.
     *
     * <p>{@code userId} is never nullable here — the {@code user_id = :userId}
     * predicate is applied unconditionally. Rows with {@code user_id IS NULL}
     * (legacy rows from before tenant scoping) are therefore invisible to
     * every caller; that is intentional.
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
            WHERE user_id = :userId
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
            WHERE user_id = :userId
              AND (CAST(:status AS TEXT) IS NULL OR UPPER(status) = UPPER(CAST(:status AS TEXT)))
              AND (CAST(:strategyCode AS TEXT) IS NULL OR strategy_code ILIKE CONCAT(CAST(:strategyCode AS TEXT), '%'))
              AND (CAST(:symbol AS TEXT) IS NULL OR asset ILIKE CONCAT(CAST(:symbol AS TEXT), '%'))
              AND (CAST(:intervalName AS TEXT) IS NULL OR interval_name = CAST(:intervalName AS TEXT))
              AND (CAST(:fromDate AS TIMESTAMP) IS NULL OR created_time >= CAST(:fromDate AS TIMESTAMP))
              AND (CAST(:toDate AS TIMESTAMP) IS NULL OR created_time <= CAST(:toDate AS TIMESTAMP))
            """, nativeQuery = true)
    long countFiltered(
            @Param("userId") UUID userId,
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
     * interval, paginated and ordered newest-first.
     *
     * <p>{@code userId} is matched permissively — rows with {@code user_id IS NULL}
     * (legacy / orchestrator-driven runs) are visible to every admin caller so
     * agent-produced research isn't hidden behind a tenant boundary the agent
     * never sets. This mirrors the prior in-memory filter in
     * {@code ResearchController.getResearchLog}.
     *
     * <p>Each filter is nullable. {@code CAST(:p AS TEXT)} pins the JDBC type
     * (otherwise Hibernate infers {@code bytea} for nullable strings, breaking
     * {@code UPPER(?)} / {@code ILIKE ?} on Postgres).
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
            ORDER BY created_time DESC NULLS LAST
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
            """,
           nativeQuery = true)
    Page<BacktestRun> findResearchLog(
            @Param("userId") UUID userId,
            @Param("strategyCode") String strategyCode,
            @Param("asset") String asset,
            @Param("intervalName") String intervalName,
            Pageable pageable);

    /**
     * Fetch a single run by id, scoped to its owner. Returns empty if the run
     * exists but belongs to a different user — callers must collapse "not found"
     * and "not yours" into the same response.
     */
    @Query(value = """
            SELECT * FROM backtest_run
            WHERE backtest_run_id = :id
              AND user_id = :userId
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
