package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PortfolioAssetResponse {
    private String asset;
    private BigDecimal free;
    private BigDecimal locked;
    private BigDecimal usdtValue;
}
