package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyDailyRealizedCurve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyDailyRealizedCurveRepository extends JpaRepository<StrategyDailyRealizedCurve, UUID> {

    @Query(value = """
            SELECT *
            FROM strategy_daily_realized_curve
            WHERE account_strategy_id = :accountStrategyId
              AND curve_date < :curveDate
            ORDER BY curve_date DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<StrategyDailyRealizedCurve> findTopByAccountStrategyIdAndCurveDateBeforeOrderByCurveDateDesc(
            @Param("accountStrategyId") UUID accountStrategyId,
            @Param("curveDate") LocalDate curveDate
    );

    @Query(value = """
            SELECT *
            FROM strategy_daily_realized_curve
            WHERE account_strategy_id = :accountStrategyId
              AND curve_date = :curveDate
            LIMIT 1
            """, nativeQuery = true)
    Optional<StrategyDailyRealizedCurve> findByAccountStrategyIdAndCurveDate(
            @Param("accountStrategyId") UUID accountStrategyId,
            @Param("curveDate") LocalDate curveDate
    );

    @Query(value = """
            SELECT *
            FROM strategy_daily_realized_curve
            WHERE curve_date = :curveDate
            ORDER BY account_strategy_id ASC
            """, nativeQuery = true)
    List<StrategyDailyRealizedCurve> findByCurveDate(
            @Param("curveDate") LocalDate curveDate
    );

    /**
     * Batch fetch: for each accountStrategyId in the list, returns the single most-recent
     * curve row strictly before curveDate (i.e. the "previous" curve). Uses DISTINCT ON
     * so the result has at most one row per accountStrategyId.
     */
    @Query(value = """
            SELECT DISTINCT ON (account_strategy_id) *
            FROM strategy_daily_realized_curve
            WHERE account_strategy_id IN (:accountStrategyIds)
              AND curve_date < :curveDate
            ORDER BY account_strategy_id, curve_date DESC
            """, nativeQuery = true)
    List<StrategyDailyRealizedCurve> findLatestBeforeDateForStrategies(
            @Param("accountStrategyIds") Collection<UUID> accountStrategyIds,
            @Param("curveDate") LocalDate curveDate
    );

    /**
     * Batch fetch: returns all curve rows for the given accountStrategyIds on exactly curveDate.
     */
    @Query(value = """
            SELECT *
            FROM strategy_daily_realized_curve
            WHERE account_strategy_id IN (:accountStrategyIds)
              AND curve_date = :curveDate
            """, nativeQuery = true)
    List<StrategyDailyRealizedCurve> findByAccountStrategyIdsAndCurveDate(
            @Param("accountStrategyIds") Collection<UUID> accountStrategyIds,
            @Param("curveDate") LocalDate curveDate
    );

    /**
     * Continuous slice of daily curve rows in {@code [startDate, endDate]} inclusive,
     * ordered ASC. Used by {@code PnlDeviationAlertService} to z-score the most
     * recent week against a trailing baseline.
     */
    @Query(value = """
            SELECT *
            FROM strategy_daily_realized_curve
            WHERE account_strategy_id = :accountStrategyId
              AND curve_date BETWEEN :startDate AND :endDate
            ORDER BY curve_date ASC
            """, nativeQuery = true)
    List<StrategyDailyRealizedCurve> findByAccountStrategyIdAndCurveDateBetween(
            @Param("accountStrategyId") UUID accountStrategyId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}