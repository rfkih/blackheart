package id.co.blackheart.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.util.UUID;

/**
 * SECURITY: {@code apiKey}/{@code apiSecret} are {@link JsonIgnore}d. The client
 * supplies {@code accountId}; the controller looks up credentials after
 * verifying JWT ownership.
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

    @JsonIgnore
    @ToString.Exclude
    private String apiKey;

    @JsonIgnore
    @ToString.Exclude
    private String apiSecret;
}