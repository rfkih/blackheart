package id.co.blackheart.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
@AllArgsConstructor
@Builder
public class MarketOrderRequest {
    private String symbol;
    private int side;
    private BigDecimal amount;
    private boolean isQuoteQty;
    private String apiKey;
    private String apiSecret;
}
