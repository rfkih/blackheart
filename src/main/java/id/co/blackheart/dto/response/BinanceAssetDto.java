package id.co.blackheart.dto.response;


import lombok.*;

@Data
@NoArgsConstructor
public class BinanceAssetDto {
    private String asset;
    private String free;
    private String locked;
    private String freeze;
    private String withdrawing;
    private String ipoable;
    private String btcValuation;
}
