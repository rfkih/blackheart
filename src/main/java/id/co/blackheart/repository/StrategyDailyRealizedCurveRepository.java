package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyDailyRealizedCurve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
}