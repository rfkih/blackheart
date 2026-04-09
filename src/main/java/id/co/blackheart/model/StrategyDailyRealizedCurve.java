package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "strategy_daily_realized_curve",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_strategy_daily_realized_curve", columnNames = {"account_strategy_id", "curve_date"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyDailyRealizedCurve {

    @Id
    @Column(name = "strategy_daily_realized_curve_id", nullable = false, updatable = false)
    private UUID strategyDailyRealizedCurveId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_strategy_id", nullable = false)
    private UUID accountStrategyId;

    @Column(name = "curve_date", nullable = false)
    private LocalDate curveDate;

    @Column(name = "daily_realized_pnl_amount", nullable = false, precision = 24, scale = 12)
    private BigDecimal dailyRealizedPnlAmount;

    @Column(name = "cumulative_realized_pnl_amount", nullable = false, precision = 24, scale = 12)
    private BigDecimal cumulativeRealizedPnlAmount;

    @Column(name = "daily_closed_notional", nullable = false, precision = 24, scale = 12)
    private BigDecimal dailyClosedNotional;

    @Column(name = "daily_weighted_return_pct", nullable = false, precision = 24, scale = 12)
    private BigDecimal dailyWeightedReturnPct;

    @Column(name = "cumulative_weighted_return_index", nullable = false, precision = 24, scale = 12)
    private BigDecimal cumulativeWeightedReturnIndex;

    @Column(name = "closed_position_count", nullable = false)
    private Integer closedPositionCount;

    @Column(name = "win_position_count", nullable = false)
    private Integer winPositionCount;

    @Column(name = "loss_position_count", nullable = false)
    private Integer lossPositionCount;

    @Column(name = "breakeven_position_count", nullable = false)
    private Integer breakevenPositionCount;

    @Column(name = "calculation_version", nullable = false, length = 20)
    private String calculationVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
