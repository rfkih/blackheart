package id.co.blackheart.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BinanceOrderRequest {
    private String symbol;
    private String side;
    private BigDecimal amount;
    private String apiKey;
    private String apiSecret;
}