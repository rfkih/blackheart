package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyConfig;
import id.co.blackheart.projection.TrendFollowingConfigProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfig, UUID> {

    @Query(value = """
        SELECT
            sc.strategy_config_id AS strategyConfigId,
            tfcd.trend_following_config_detail_id AS trendFollowingConfigDetailId,
            sc.strategy_name AS strategyName,
            sc.interval_name AS intervalName,
            sc.symbol AS symbol,
            sc.status AS status,
            sc.version AS version,
            sc.enabled AS enabled,
            tfcd.min_adx AS minAdx,
            tfcd.min_efficiency_ratio AS minEfficiencyRatio,
            tfcd.min_relative_volume AS minRelativeVolume,
            tfcd.stop_atr_multiplier AS stopAtrMultiplier,
            tfcd.take_profit_atr_multiplier AS takeProfitAtrMultiplier,
            tfcd.trailing_atr_multiplier AS trailingAtrMultiplier,
            tfcd.allow_long AS allowLong,
            tfcd.allow_short AS allowShort,
            tfcd.allow_breakout_entry AS allowBreakoutEntry,
            tfcd.allow_pullback_entry AS allowPullbackEntry,
            tfcd.allow_bias_entry AS allowBiasEntry
        FROM strategy_config sc
        JOIN trend_following_config_detail tfcd
          ON tfcd.strategy_config_id = sc.strategy_config_id
        WHERE sc.strategy_name = :strategyName
          AND sc.interval_name = :intervalName
          AND sc.symbol = :symbol
          AND sc.status = 'ACTIVE'
          AND sc.enabled = TRUE
        ORDER BY sc.version DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<TrendFollowingConfigProjection> findActiveBySymbol(
            @Param("strategyName") String strategyName,
            @Param("intervalName") String intervalName,
            @Param("symbol") String symbol
    );

    @Query(value = """
        SELECT
            sc.strategy_config_id AS strategyConfigId,
            tfcd.trend_following_config_detail_id AS trendFollowingConfigDetailId,
            sc.strategy_name AS strategyName,
            sc.interval_name AS intervalName,
            sc.symbol AS symbol,
            sc.status AS status,
            sc.version AS version,
            sc.enabled AS enabled,
            tfcd.min_adx AS minAdx,
            tfcd.min_efficiency_ratio AS minEfficiencyRatio,
            tfcd.min_relative_volume AS minRelativeVolume,
            tfcd.stop_atr_multiplier AS stopAtrMultiplier,
            tfcd.take_profit_atr_multiplier AS takeProfitAtrMultiplier,
            tfcd.trailing_atr_multiplier AS trailingAtrMultiplier,
            tfcd.allow_long AS allowLong,
            tfcd.allow_short AS allowShort,
            tfcd.allow_breakout_entry AS allowBreakoutEntry,
            tfcd.allow_pullback_entry AS allowPullbackEntry,
            tfcd.allow_bias_entry AS allowBiasEntry
        FROM strategy_config sc
        JOIN trend_following_config_detail tfcd
          ON tfcd.strategy_config_id = sc.strategy_config_id
        WHERE sc.strategy_name = :strategyName
          AND sc.interval_name = :intervalName
          AND sc.symbol IS NULL
          AND sc.status = 'ACTIVE'
          AND sc.enabled = TRUE
        ORDER BY sc.version DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<TrendFollowingConfigProjection> findActiveDefault(
            @Param("strategyName") String strategyName,
            @Param("intervalName") String intervalName
    );
}
