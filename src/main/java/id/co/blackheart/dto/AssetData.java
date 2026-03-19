package id.co.blackheart.dto;

import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class AssetData {
    private String asset;
    private String free;
    private String locked;

    @Override
    public String toString() {
        return "AssetData{" +
                "asset='" + asset + '\'' +
                ", free='" + free + '\'' +
                ", locked='" + locked + '\'' +
                '}';
    }
}
