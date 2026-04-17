package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trade_execution_log")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeExecutionLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trade_execution_log_id", nullable = false, updatable = false)
    private UUID tradeExecutionLogId;

    /** OPEN or CLOSE */
    @Column(name = "execution_type", nullable = false, length = 10)
    private String executionType;

    /** LONG or SHORT */
    @Column(name = "side", length = 10)
    private String side;

    /** SUCCESS or FAILED */
    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "asset", length = 20)
    private String asset;

    @Column(name = "strategy_name", length = 100)
    private String strategyName;

    /** Entry reason (OPEN) or exit reason (CLOSE) */
    @Column(name = "execution_reason", length = 200)
    private String executionReason;

    /** Null if the order was rejected before a trade record was created */
    @Column(name = "trade_id")
    private UUID tradeId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;
}
