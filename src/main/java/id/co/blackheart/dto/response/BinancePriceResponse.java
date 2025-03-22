package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BinancePriceResponse {
    private String symbol;
    private String price;
}
