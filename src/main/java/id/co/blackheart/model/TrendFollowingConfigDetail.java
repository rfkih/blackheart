package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trend_following_config_detail")
public class TrendFollowingConfigDetail extends BaseEntity {

    @Id
    @Column(name = "trend_following_config_detail_id", nullable = false, updatable = false)
    private UUID trendFollowingConfigDetailId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_config_id", nullable = false, unique = true)
    private StrategyConfig strategyConfig;

    @Column(name = "min_adx", nullable = false, precision = 18, scale = 8)
    private BigDecimal minAdx;

    @Column(name = "min_efficiency_ratio", nullable = false, precision = 18, scale = 8)
    private BigDecimal minEfficiencyRatio;

    @Column(name = "min_relative_volume", nullable = false, precision = 18, scale = 8)
    private BigDecimal minRelativeVolume;

    @Column(name = "stop_atr_multiplier", nullable = false, precision = 18, scale = 8)
    private BigDecimal stopAtrMultiplier;

    @Column(name = "take_profit_atr_multiplier", nullable = false, precision = 18, scale = 8)
    private BigDecimal takeProfitAtrMultiplier;

    @Column(name = "trailing_atr_multiplier", nullable = false, precision = 18, scale = 8)
    private BigDecimal trailingAtrMultiplier;

    @Column(name = "allow_long", nullable = false)
    private Boolean allowLong;

    @Column(name = "allow_short", nullable = false)
    private Boolean allowShort;

    @Column(name = "allow_breakout_entry", nullable = false)
    private Boolean allowBreakoutEntry;

    @Column(name = "allow_pullback_entry", nullable = false)
    private Boolean allowPullbackEntry;

    @Column(name = "allow_bias_entry", nullable = false)
    private Boolean allowBiasEntry;

}