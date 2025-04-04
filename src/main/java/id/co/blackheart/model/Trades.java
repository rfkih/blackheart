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
    private Long userId; // Link to the user making the trade

    @Column(nullable = false)
    private String tradePlan; // Trade Plan

    @Column(nullable = false)
    private Long entryOrderId;

    @Column
    private Long exitOrderId;

    @Column(nullable = false)
    private String asset; // BTC, ETH, etc.

    @Column(nullable = false)
    private String action; // BUY or SELL

    @Column(nullable = false, precision = 20, scale = 12)
    private BigDecimal entryExecutedQty;

    @Column(nullable = false, precision = 20, scale = 12)
    private BigDecimal entryExecutedQuoteQty;

    @Column(precision = 20, scale = 12)
    private BigDecimal exitExecutedQty;

    @Column(precision = 20, scale = 12)
    private BigDecimal exitExecutedQuoteQty;

    @Column(precision = 20, scale = 12)
    private BigDecimal entryFee;

    @Column(precision = 20, scale = 12)
    private BigDecimal exitFee;

    @Column(nullable = false, precision = 20, scale = 12)
    private BigDecimal entryPrice; // Price at trade execution

    @Column(precision = 20, scale = 12)
    private BigDecimal exitPrice; // Price at exit trade

    @Column(precision = 5, scale = 2)
    private BigDecimal plPercent;

    @Column(precision = 20, scale = 12)
    private BigDecimal plAmount;

    @Column(nullable = false, precision = 20, scale = 12)
    private BigDecimal stopLossPrice; // Stop-loss level

    @Column(nullable = false, precision = 20, scale = 12)
    private BigDecimal takeProfitPrice; // Take-profit level

    @Column(nullable = false)
    private String isActive; // Indicates if trade is still open

    @Column(nullable = false)
    private String feeCurrency;

    @Column(nullable = false)
    private LocalDateTime entryTime; // Timestamp when trade was executed

    @Column
    private LocalDateTime exitTime; // Timestamp when trade was ended
}

