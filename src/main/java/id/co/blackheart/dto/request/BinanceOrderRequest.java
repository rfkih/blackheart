package id.co.blackheart.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * <p>SECURITY: {@code apiKey}/{@code apiSecret} are {@link JsonIgnore}d — they
 * are NEVER deserialized from client JSON. The controller looks up the account
 * by {@code accountId} (after verifying JWT ownership) and injects the decrypted
 * credentials before forwarding the request. Internal callers
 * ({@code TradeOpenService}, {@code TradeCloseService}) set these fields directly
 * via the Lombok builder/setters — that path is unaffected.
 *
 * <p>They are also excluded from the Lombok {@code toString()} so a stray
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

    @JsonIgnore
    @ToString.Exclude
    private String apiKey;

    @JsonIgnore
    @ToString.Exclude
    private String apiSecret;
}