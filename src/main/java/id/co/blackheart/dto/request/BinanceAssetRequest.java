package id.co.blackheart.dto.request;
import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BinanceAssetRequest {
    private String asset;
    private String apiKey;
    private String apiSecret;
    private int recvWindow;
}
