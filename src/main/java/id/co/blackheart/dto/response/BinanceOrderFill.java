package id.co.blackheart.dto.response;

import lombok.Data;

@Data
public class BinanceOrderFill {
    private String price;
    private String qty;
    private String commission;
    private String commissionAsset;
    private Long tradeId;
}