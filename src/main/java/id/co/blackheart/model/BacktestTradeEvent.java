package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backtest_trade_event")
public class BacktestTradeEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "backtest_trade_event_id", nullable = false, updatable = false)
    private UUID backtestTradeEventId;

    @Column(name = "backtest_run_id", nullable = false)
    private UUID backtestRunId;

    @Column(name = "trade_id")
    private UUID tradeId;

    @Column(name = "trade_position_id")
    private UUID tradePositionId;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(name = "candle_time")
    private LocalDateTime candleTime;

    @Column(name = "price", precision = 24, scale = 8)
    private BigDecimal price;

    @Column(name = "qty", precision = 24, scale = 8)
    private BigDecimal qty;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

}