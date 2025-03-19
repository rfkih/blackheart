package id.co.blackheart.dto.request;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class BinanceAssetRequest {
    private String asset;
    private String apiKey;
    private String apiSecret;
    private int recvWindow;
}
