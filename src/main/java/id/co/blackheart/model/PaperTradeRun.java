package id.co.blackheart.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One simulated trade-decision event from the live executor while the
 * owning account_strategy has {@code simulated=true}. The hypothetical
 * "would-have-been-placed" order is captured here in lieu of an actual
 * order to Binance; signal quality and edge realization can then be
 * assessed before real capital is committed.
 *
 * <p>Schema: see {@code db/flyway/V15__create_promotion_pipeline.sql}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "paper_trade_run")
public class PaperTradeRun {

    @Id
    @Column(name = "paper_trade_id", nullable = false, updatable = false)
    private UUID paperTradeId;

    @Column(name = "account_strategy_id", nullable = false)
    private UUID accountStrategyId;

    @Column(name = "strategy_code", nullable = false, length = 20)
    private String strategyCode;

    @Column(name = "symbol", nullable = false, length = 30)
    private String symbol;

    @Column(name = "interval_name", nullable = false, length = 20)
    private String intervalName;

    @Column(name = "decision_type", nullable = false, length = 40)
    private String decisionType;

    @Column(name = "side", length = 10)
    private String side;

    @Column(name = "intended_price", precision = 20, scale = 8)
    private BigDecimal intendedPrice;

    @Column(name = "intended_quantity", precision = 20, scale = 8)
    private BigDecimal intendedQuantity;

    @Column(name = "intended_notional_usdt", precision = 20, scale = 8)
    private BigDecimal intendedNotionalUsdt;

    @Column(name = "stop_loss_price", precision = 20, scale = 8)
    private BigDecimal stopLossPrice;

    @Column(name = "take_profit_price", precision = 20, scale = 8)
    private BigDecimal takeProfitPrice;

    @Column(name = "decision_snapshot", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode decisionSnapshot;

    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode contextSnapshot;

    @Column(name = "skip_reason", nullable = false, length = 40)
    @Builder.Default
    private String skipReason = "SIMULATED";

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;
}
