package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Trades {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String strategyName;

    @Column(nullable = false, length = 10)
    private String interval;

    @Column(nullable = false, length = 10)
    private String exchange;

    @Column(nullable = false, length = 20)
    private String asset;

    @Column(nullable = false, length = 10)
    private String side; // LONG or SHORT

    @Column(nullable = false, length = 20)
    private String status; // OPEN, CLOSED, CANCELLED, FAILED

    @Column(nullable = false)
    private Long entryOrderId;

    @Column
    private Long exitOrderId;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal entryExecutedQty;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal entryExecutedQuoteQty;

    @Column(precision = 24, scale = 12)
    private BigDecimal exitExecutedQty;

    @Column(precision = 24, scale = 12)
    private BigDecimal exitExecutedQuoteQty;

    @Column(precision = 24, scale = 12)
    private BigDecimal entryFee;

    @Column(length = 20)
    private String entryFeeCurrency;

    @Column(precision = 24, scale = 12)
    private BigDecimal exitFee;

    @Column(length = 20)
    private String exitFeeCurrency;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal entryPrice;

    @Column(precision = 24, scale = 12)
    private BigDecimal exitPrice;

    @Column(precision = 12, scale = 6)
    private BigDecimal plPercent;

    @Column(precision = 24, scale = 12)
    private BigDecimal plAmount;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal initialStopLossPrice;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal currentStopLossPrice;

    @Column(precision = 24, scale = 12)
    private BigDecimal trailingStopPrice;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal takeProfitPrice;

    @Column(length = 30)
    private String exitReason; // STOP_LOSS, TAKE_PROFIT, TRAILING_STOP, TIME_STOP, REGIME_REVERSAL, MANUAL_CLOSE

    @Column(length = 10)
    private String entryTrendRegime;

    @Column(precision = 24, scale = 8)
    private BigDecimal entryAdx;

    @Column(precision = 24, scale = 8)
    private BigDecimal entryAtr;

    @Column(precision = 24, scale = 8)
    private BigDecimal entryRsi;

    @Column(nullable = false)
    private LocalDateTime entryTime;

    @Column
    private LocalDateTime exitTime;
}