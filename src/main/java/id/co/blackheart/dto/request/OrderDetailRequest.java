package id.co.blackheart.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderDetailRequest implements Serializable {
    private String orderId;
    private Integer recvWindow;
    private String apiKey;
    private String apiSecret;
}
