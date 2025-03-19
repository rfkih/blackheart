package id.co.blackheart.dto.request;

import lombok.Data;

@Data
public class BinanceOrderRequest {
    private String symbol;
    private String side;
    private double amount;
    private String apiKey;
    private String apiSecret;
}