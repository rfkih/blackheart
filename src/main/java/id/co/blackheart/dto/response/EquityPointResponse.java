package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One sample on the account equity curve. Frontend uses this to render
 * the dashboard equity chart and drawdown sub-chart.
 *
 * <ul>
 *   <li>{@code time}     — epoch milliseconds (UTC) for the sample.</li>
 *   <li>{@code equity}   — absolute account equity in USDT at that point in time.</li>
 *   <li>{@code drawdown} — percentage drop from the running peak, expressed as a
 *       negative number (e.g. {@code -5.2} means -5.2%). Always {@code ≤ 0}.</li>
 * </ul>
 */
@Data
@Builder
public class EquityPointResponse {
    private Long time;
    private BigDecimal equity;
    private BigDecimal drawdown;
}