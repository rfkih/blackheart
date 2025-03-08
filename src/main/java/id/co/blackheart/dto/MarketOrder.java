package id.co.blackheart.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
@AllArgsConstructor
public class MarketOrder {
    private String symbol;
    private int side;
    private BigDecimal amount;
    private boolean isQuoteQty;
    private String apiKey;
    private String apiSecret;
}
