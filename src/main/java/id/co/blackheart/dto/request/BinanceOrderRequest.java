package id.co.blackheart.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Request envelope for a Binance market order. Carries the account's API
 * credentials alongside the trade parameters — {@code apiKey}/{@code apiSecret}
 * are explicitly excluded from Lombok's generated {@code toString()} so a
 * stray {@code log.info("order: {}", req)} can't leak them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BinanceOrderRequest {
    private String symbol;
    private String side;
    private BigDecimal amount;

    @ToString.Exclude
    private String apiKey;

    @ToString.Exclude
    private String apiSecret;
}