package id.co.blackheart.dto.request;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BinanceOrderRequest {
    private String symbol;
    private String side;
    private BigDecimal amount;
    private String apiKey;
    private String apiSecret;
}