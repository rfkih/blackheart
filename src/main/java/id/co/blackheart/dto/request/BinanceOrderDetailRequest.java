package id.co.blackheart.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.util.UUID;

/**
 * SECURITY: {@code apiKey}/{@code apiSecret} use {@code READ_ONLY} access —
 * outbound serialisation to the Node Binance proxy includes them (the proxy
 * requires both), but client JSON cannot supply them (the controller injects
 * after JWT-ownership lookup). See BinanceOrderRequest for the parallel
 * rationale and the prior {@code @JsonIgnore} bug.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BinanceOrderDetailRequest implements Serializable {
    private String orderId;
    private String symbol;
    private Integer recvWindow;
    private UUID accountId;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @ToString.Exclude
    private String apiKey;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @ToString.Exclude
    private String apiSecret;
}