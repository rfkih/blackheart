package id.co.blackheart.dto.response;

import lombok.*;

import java.util.List;


@Data
@NoArgsConstructor
public class BinanceAssetResponse {
    private List<BinanceAssetDto> assets;
}
