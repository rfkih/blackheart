package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trades")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Trades extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trade_id", nullable = false, updatable = false)
    private UUID tradeId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_strategy_id", nullable = false)
    private UUID accountStrategyId;

    @Column(name = "strategy_name", nullable = false, length = 100)
    private String strategyName;

    @Column(name = "interval", nullable = false, length = 20)
    private String interval;

    @Column(name = "exchange", nullable = false, length = 20)
    private String exchange;

    @Column(name = "asset", nullable = false, length = 30)
    private String asset;

    @Column(name = "side", nullable = false, length = 10)
    private String side; // LONG / SHORT

    @Column(name = "status", nullable = false, length = 30)
    private String status; // OPEN / PARTIALLY_CLOSED / CLOSED / CANCELLED

    @Column(name = "trade_mode", nullable = false, length = 30)
    private String tradeMode; // SINGLE / MULTI_SLICE / RUNNER

    @Column(name = "avg_entry_price", nullable = false, precision = 24, scale = 12)
    private BigDecimal avgEntryPrice;

    @Column(name = "avg_exit_price", precision = 24, scale = 12)
    private BigDecimal avgExitPrice;

    @Column(name = "total_entry_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal totalEntryQty;

    @Column(name = "total_entry_quote_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal totalEntryQuoteQty;

    @Column(name = "total_remaining_qty", nullable = false, precision = 24, scale = 12)
    private BigDecimal totalRemainingQty;

    @Column(name = "realized_pnl_amount", precision = 24, scale = 12)
    private BigDecimal realizedPnlAmount;

    @Column(name = "realized_pnl_percent", precision = 12, scale = 6)
    private BigDecimal realizedPnlPercent;

    @Column(name = "total_fee_amount", precision = 24, scale = 12)
    private BigDecimal totalFeeAmount;

    @Column(name = "total_fee_currency", length = 20)
    private String totalFeeCurrency;

    @Column(name = "exit_reason", length = 50)
    private String exitReason;

    @Column(name = "entry_trend_regime", length = 20)
    private String entryTrendRegime;

    @Column(name = "entry_adx", precision = 24, scale = 8)
    private BigDecimal entryAdx;

    @Column(name = "entry_atr", precision = 24, scale = 8)
    private BigDecimal entryAtr;

    @Column(name = "entry_rsi", precision = 24, scale = 8)
    private BigDecimal entryRsi;

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    /**
     * Decision-time intent (Phase 2c). Captured by the live executor
     * BEFORE vol-targeting scales the size, used at trade close to
     * decompose realized P&L into signal alpha + execution drift +
     * sizing residual. Nullable on legacy rows.
     *
     * <p>{@code intendedSize} mirrors the side's natural unit:
     * USDT for LONG (notional), BTC qty for SHORT (position).
     */
    @Column(name = "intended_entry_price", precision = 24, scale = 12)
    private BigDecimal intendedEntryPrice;

    @Column(name = "intended_size", precision = 24, scale = 12)
    private BigDecimal intendedSize;

    @Column(name = "decision_time")
    private LocalDateTime decisionTime;

}