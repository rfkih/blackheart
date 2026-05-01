package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stuck-trade anomaly surfaced on the admin reconciliation panel.
 *
 * <p>{@code anomalyType} categorises the problem:
 * <ul>
 *   <li>{@code OPEN_NO_CHILDREN}        — parent OPEN, zero rows in
 *       trade_positions. Open never produced a leg.</li>
 *   <li>{@code OPEN_NO_OPEN_CHILDREN}   — parent OPEN, every child CLOSED.
 *       Listener never flipped the parent.</li>
 *   <li>{@code PARTIAL_NO_OPEN_CHILDREN} — PARTIALLY_CLOSED parent with no
 *       OPEN legs. Per CLAUDE.md domain invariant the parent should be CLOSED.</li>
 * </ul>
 */
@Data
@Builder
public class TradeAnomalyResponse {

    private UUID tradeId;
    private UUID accountId;
    private UUID accountStrategyId;
    private String asset;
    private String interval;
    private String side;
    private String status;
    private LocalDateTime entryTime;
    private long totalLegs;
    private long openLegs;
    private String anomalyType;
}
