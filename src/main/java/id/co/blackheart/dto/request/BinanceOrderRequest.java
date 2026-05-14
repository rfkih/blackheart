package id.co.blackheart.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request envelope for a Binance market order.
 *
 * <p>SECURITY: {@code apiKey}/{@code apiSecret} use
 * {@code @JsonProperty(access = READ_ONLY)} — Jackson INCLUDES them when
 * serialising Java → JSON (so the outbound POST body to the Node Binance
 * proxy at {@code /api/place-market-order-binance} carries the credentials
 * the proxy validation requires), but IGNORES them when deserialising
 * JSON → Java (so a client request body can never supply its own
 * credentials; the controller must inject them after JWT-ownership lookup).
 *
 * <p>The previous {@code @JsonIgnore} blocked BOTH directions, which silently
 * stripped credentials from the outbound proxy call and caused the Node
 * proxy to reject every order with "apiKey: Required, apiSecret: Required".
 *
 * <p>Internal callers ({@code TradeOpenService}, {@code TradeCloseService})
 * set these fields directly via the Lombok builder/setters — unaffected.
 *
 * <p>They are still excluded from the Lombok {@code toString()} so a stray
 * {@code log.info("order: {}", req)} cannot leak them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BinanceOrderRequest {
    private String symbol;
    private String side;
    private BigDecimal amount;

    /** Account whose credentials should be used. Required for public trade endpoint. */
    private UUID accountId;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @ToString.Exclude
    private String apiKey;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @ToString.Exclude
    private String apiSecret;
}