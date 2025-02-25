package id.co.blackheart.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;


@Data
public class AssetDto {
    @JsonProperty("asset")
    private String asset;

    @JsonProperty("free")
    private BigDecimal free;

    @JsonProperty("locket")
    private BigDecimal locket;

}
